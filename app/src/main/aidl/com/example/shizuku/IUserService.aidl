package com.example.shizuku;

interface IUserService {
    // Reserved transaction code: Shizuku calls this when the service must stop.
    void destroy() = 16777114;

    void exit() = 1;

    // Runs "sh -c <command>" with shell (or root) identity and returns the output.
    String exec(String command) = 2;
}
