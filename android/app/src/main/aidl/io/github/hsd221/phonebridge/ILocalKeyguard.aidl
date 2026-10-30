package io.github.hsd221.phonebridge;

// APK-local Binder only. No command strings, arbitrary targets, UI dumps or remote arguments.
interface ILocalKeyguard {
    int uid() = 0;
    int prepare() = 1;
    int preflight(int kind) = 2;
    int digit(int digit, boolean first) = 3;
    int confirmPin() = 4;
    int password(String value) = 5;
    int confirmPassword() = 6;
    int pattern(String value) = 7;
    int reason() = 8;
    void destroy() = 16777114;
}
