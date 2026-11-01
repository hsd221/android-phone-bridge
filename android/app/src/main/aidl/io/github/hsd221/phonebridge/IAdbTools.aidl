package io.github.hsd221.phonebridge;

/** Fixed, argument-validated input operations exposed through the shell UID. */
interface IAdbTools {
    int uid();
    int tap(int x, int y);
    int swipe(int fromX, int fromY, int toX, int toY, int durationMs);
    int inputText(String text);
    int back();
}
