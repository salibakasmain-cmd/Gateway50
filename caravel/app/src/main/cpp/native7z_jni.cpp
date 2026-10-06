#include <jni.h>

#include <algorithm>
#include <exception>
#include <filesystem>
#include <stdexcept>
#include <string>

#include "bit7z/bitextractor.hpp"
#include "bit7z/bitformat.hpp"

namespace {

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

std::string lower(std::string value) {
    std::transform(
        value.begin(),
        value.end(),
        value.begin(),
        [](unsigned char c) {
            return static_cast<char>(std::tolower(c));
        }
    );
    return value;
}

void configureExtractor(
    bit7z::BitExtractor<std::string>& extractor,
    const std::string& password
) {
    extractor.setRetainDirectories(true);
    extractor.setOverwriteMode(
        bit7z::OverwriteMode::Overwrite
    );

    if (!password.empty()) {
        extractor.setPassword(password);
    }
}

void extractGzipThenTar(
    bit7z::Bit7zLibrary& library,
    const std::string& archivePath,
    const std::string& destinationPath,
    const std::string& password
) {
    const std::filesystem::path stageDir =
        std::filesystem::path(destinationPath).parent_path() /
        (std::filesystem::path(destinationPath).filename().string() +
         ".gzip-stage");

    std::error_code ec;
    std::filesystem::remove_all(stageDir, ec);

    if (!std::filesystem::create_directories(stageDir, ec) && ec) {
        throw std::runtime_error(
            "Unable to create gzip staging directory: " +
            stageDir.string()
        );
    }

    try {
        bit7z::BitExtractor<std::string> gzipExtractor(
            library,
            bit7z::BitFormat::GZip
        );
        configureExtractor(gzipExtractor, password);
        gzipExtractor.extract(
            archivePath,
            stageDir.string()
        );

        std::filesystem::path tarPath;
        std::size_t regularFileCount = 0;

        for (
            const auto& entry :
            std::filesystem::recursive_directory_iterator(
                stageDir,
                std::filesystem::directory_options::skip_permission_denied,
                ec
            )
        ) {
            if (ec) {
                break;
            }

            std::error_code typeEc;
            if (!entry.is_regular_file(typeEc) || typeEc) {
                continue;
            }

            const std::string name =
                lower(entry.path().filename().string());

            if (name.size() >= 4 &&
                name.compare(
                    name.size() - 4,
                    4,
                    ".tar"
                ) == 0) {
                tarPath = entry.path();
                ++regularFileCount;
            }
        }

        if (regularFileCount != 1 || tarPath.empty()) {
            throw std::runtime_error(
                "GZIP archive did not produce exactly one TAR payload"
            );
        }

        bit7z::BitExtractor<std::string> tarExtractor(
            library,
            bit7z::BitFormat::Tar
        );
        configureExtractor(tarExtractor, password);
        tarExtractor.extract(
            tarPath.string(),
            destinationPath
        );
    } catch (...) {
        std::filesystem::remove_all(stageDir, ec);
        throw;
    }

    std::filesystem::remove_all(stageDir, ec);
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

    /*
     * CARAVEL uses 7-Zip/bit7z for Android-safe archive extraction.
     *
     * Hermes is downloaded from GitHub codeload as TAR.GZ.  Treat the
     * archive as two layers: GZIP first, then TAR.  This is intentional;
     * forcing a TAR.GZ through BitFormat::SevenZip reports:
     * "Invalid archive, or wrong format used."
     *
     * The TAR layer is then extracted directly by 7-Zip, preserving the
     * POSIX metadata handling already proven for the Ubuntu rootfs.
     */
    const std::string name = lower(
        std::filesystem::path(archivePath).filename().string()
    );

    if ((name.size() >= 7 &&
         name.compare(name.size() - 7, 7, ".tar.gz") == 0) ||
        (name.size() >= 4 &&
         name.compare(name.size() - 4, 4, ".tgz") == 0)) {
        extractGzipThenTar(
            library,
            archivePath,
            destinationPath,
            password
        );
        return;
    }

    bit7z::BitExtractor<std::string> extractor(
        library,
        bit7z::BitFormat::SevenZip
    );
    configureExtractor(extractor, password);

    extractor.extract(
        archivePath,
        destinationPath
    );
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
