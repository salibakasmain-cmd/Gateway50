#include <jni.h>

#include <algorithm>
#include <exception>
#include <filesystem>
#include <fstream>
#include <stdexcept>
#include <string>
#include <system_error>
#include <utility>
#include <vector>

#include "bit7z/bitarchiveitem.hpp"
#include "bit7z/bitarchivereader.hpp"
#include "bit7z/bitextractor.hpp"
#include "bit7z/bitformat.hpp"
#include "bit7z/bitpropvariant.hpp"
#include "internal/fsutil.hpp"

namespace fs = std::filesystem;

namespace {

struct LinkInfo {
    std::string path;
    std::string target;
    bool directory;
};

std::string fromJstring(JNIEnv* env, jstring value) {
    if (value == nullptr) {
        return {};
    }

    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        throw std::runtime_error("Unable to read Java string");
    }

    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

void throwIOException(JNIEnv* env, const std::string& message) noexcept {
    jclass cls = env->FindClass("java/io/IOException");
    if (cls != nullptr) {
        env->ThrowNew(cls, message.c_str());
    }
}

bool isWithinBase(
    const fs::path& candidate,
    const fs::path& base
) {
    const std::string baseText =
        base.generic_string();
    const std::string candidateText =
        candidate.generic_string();

    return candidateText == baseText ||
        (candidateText.size() > baseText.size() &&
         candidateText.compare(
             0,
             baseText.size(),
             baseText
         ) == 0 &&
         candidateText[baseText.size()] == '/');
}

fs::path safeArchivePath(
    const fs::path& base,
    const std::string& archivePath
) {
    const fs::path root =
        fs::absolute(base).lexically_normal();

    const fs::path candidate =
        (root / fs::path(archivePath)).lexically_normal();

    if (!isWithinBase(candidate, root)) {
        throw std::runtime_error(
            "Archive link path escapes extraction directory"
        );
    }

    return candidate;
}

void removeExisting(const fs::path& path) {
    std::error_code ec;
    fs::remove_all(path, ec);

    if (ec) {
        throw std::runtime_error(
            "Unable to clear existing extraction path '" +
            path.string() +
            "': " +
            ec.message()
        );
    }
}

void materializeSymlink(
    const bit7z::SafeOutPathBuilder& builder,
    const fs::path& linkPath,
    const std::string& target
) {
    const fs::path parent =
        linkPath.parent_path();

    std::error_code ec;
    fs::create_directories(parent, ec);

    if (ec) {
        throw std::runtime_error(
            "Unable to create symlink parent '" +
            parent.string() +
            "': " +
            ec.message()
        );
    }

    removeExisting(linkPath);

    {
        std::ofstream out(
            linkPath,
            std::ios::binary |
            std::ios::trunc
        );

        if (!out.is_open()) {
            throw std::runtime_error(
                "Unable to create temporary symlink payload '" +
                linkPath.string() +
                "'"
            );
        }

        out.write(
            target.data(),
            static_cast<std::streamsize>(
                target.size()
            )
        );

        if (!out.good()) {
            throw std::runtime_error(
                "Unable to write temporary symlink payload '" +
                linkPath.string() +
                "'"
            );
        }
    }

    if (!builder.restoreSymlink(linkPath)) {
        throw std::runtime_error(
            "Unable to restore symlink '" +
            linkPath.string() +
            "' -> '" +
            target +
            "'"
        );
    }
}

void extractNative(
    const std::string& archivePath,
    const std::string& destinationPath,
    const std::string& password,
    const std::string& sevenZipLibraryPath
) {
    bit7z::Bit7zLibrary library(
        sevenZipLibraryPath
    );

    bit7z::BitArchiveReader reader(
        library,
        archivePath,
        bit7z::BitFormat::SevenZip
    );

    std::vector<LinkInfo> directoryLinks;
    std::vector<LinkInfo> fileLinks;

    for (const auto& item : reader.items()) {
        if (!item.isSymLink()) {
            continue;
        }

        const auto targetProperty =
            item.itemProperty(
                bit7z::BitProperty::SymLink
            );

        if (targetProperty.isEmpty() ||
            !targetProperty.isString()) {
            continue;
        }

        const std::string target =
            targetProperty.getNativeString();

        if (target.empty()) {
            continue;
        }

        LinkInfo info{
            item.path(),
            target,
            item.isDir()
        };

        if (info.directory) {
            directoryLinks.emplace_back(
                std::move(info)
            );
        } else {
            fileLinks.emplace_back(
                std::move(info)
            );
        }
    }

    auto depth =
        [](const LinkInfo& info) {
            return static_cast<int>(
                std::count(
                    info.path.begin(),
                    info.path.end(),
                    '/'
                )
            );
        };

    std::sort(
        directoryLinks.begin(),
        directoryLinks.end(),
        [&](const LinkInfo& a,
            const LinkInfo& b) {
            return depth(a) < depth(b);
        }
    );

    const fs::path destination =
        fs::absolute(
            destinationPath
        ).lexically_normal();

    bit7z::SafeOutPathBuilder pathBuilder(
        destination.string()
    );

    // Match the Android Hermes extraction ordering:
    // directory symlinks are established first so files
    // extracted below them resolve into their real target.
    for (const auto& info : directoryLinks) {
        materializeSymlink(
            pathBuilder,
            safeArchivePath(
                destination,
                info.path
            ),
            info.target
        );
    }

    bit7z::BitFileExtractor extractor(
        library,
        bit7z::BitFormat::SevenZip
    );

    extractor.setRetainDirectories(true);
    extractor.setOverwriteMode(
        bit7z::OverwriteMode::Overwrite
    );

    if (!password.empty()) {
        extractor.setPassword(password);
    }

    extractor.extract(
        archivePath,
        destination.string(),
        [](const bit7z::BitArchiveItem& item)
            -> std::string {
            if (item.isSymLink()) {
                return {};
            }
            return item.path();
        }
    );

    for (const auto& info : fileLinks) {
        materializeSymlink(
            pathBuilder,
            safeArchivePath(
                destination,
                info.path
            ),
            info.target
        );
    }
}

} // namespace

extern "C"
JNIEXPORT void JNICALL
Java_org_caravel_android_Native7z_nativeExtract(
    JNIEnv* env,
    jclass,
    jstring archivePath,
    jstring destinationPath,
    jstring password,
    jstring sevenZipLibraryPath
) noexcept {
    try {
        extractNative(
            fromJstring(env, archivePath),
            fromJstring(env, destinationPath),
            fromJstring(env, password),
            fromJstring(env, sevenZipLibraryPath)
        );
    } catch (const std::exception& ex) {
        if (!env->ExceptionCheck()) {
            throwIOException(
                env,
                ex.what()
            );
        }
    } catch (...) {
        if (!env->ExceptionCheck()) {
            throwIOException(
                env,
                "Unknown error during native 7-Zip extraction"
            );
        }
    }
}
