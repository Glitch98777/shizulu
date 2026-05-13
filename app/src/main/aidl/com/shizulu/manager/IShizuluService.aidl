package com.shizulu.manager;

interface IShizuluService {
    int getUid() = 1;
    String runCommand(String command) = 2;
    String runShizuleCommand(String moduleId, String command) = 3;
    void destroy() = 16777114;
}
