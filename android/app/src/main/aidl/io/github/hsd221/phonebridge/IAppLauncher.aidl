package io.github.hsd221.phonebridge;
interface IAppLauncher {
    int uid();
    int launch(String packageName, String activityName);
}
