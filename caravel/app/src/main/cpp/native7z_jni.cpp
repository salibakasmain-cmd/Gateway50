#include <jni.h>

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

void extractNative(
    const std::string& archivePath,
    const std::string& destinationPath,
    const std::string& password,
    const std::string& sevenZipLibraryPath
) {
    bit7z::Bit7zLibrary library(
        sevenZipLibraryPath
    );

    bit7z::BitExtractor<std::string> extractor(
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

    /*
     * Let bit7z perform the complete POSIX extraction lifecycle.
     *
     * In particular, Bit7z's FileExtractCallback writes symbolic-link
     * payloads first and then restores them from the archive's POSIX
     * metadata.  This is important for Ubuntu's merged-/usr layout:
     * /bin, /sbin, /lib, and /lib64 are commonly symbolic links.
     *
     * Do not filter item.isSymLink() here.  isSymLink() can be true
     * because of POSIX file attributes even when BitProperty::SymLink
     * is not populated as a string.  Filtering those entries would
     * silently delete essential links such as /bin -> usr/bin.
     *
     * BIT7Z_PATH_SANITIZATION is enabled at build time, so archive
     * paths and restored symlink targets are still validated by bit7z.
     */
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
