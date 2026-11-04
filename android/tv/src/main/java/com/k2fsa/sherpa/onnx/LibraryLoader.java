// Vendored from sherpa-onnx v2.0.1 (Apache-2.0, https://github.com/k2-fsa/sherpa-onnx), java-api.
// Android version for Friday: the native library ships in the APK (jniLibs), so it is loaded by
// name; the desktop-JVM extractor (LibraryUtils) is left out.
package com.k2fsa.sherpa.onnx;

public class LibraryLoader {
    private static volatile boolean isLoaded = false;

    static synchronized void maybeLoad() {
        if (!isLoaded) {
            System.loadLibrary("sherpa-onnx-jni");
            isLoaded = true;
        }
    }
}
