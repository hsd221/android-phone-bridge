package io.github.hsd221.phonebridge;

/** Read text only, for the user running this APK. No arbitrary Binder or shell access. */
interface IClipboardReader {
    int uid();
    String readText(int userId);
}
