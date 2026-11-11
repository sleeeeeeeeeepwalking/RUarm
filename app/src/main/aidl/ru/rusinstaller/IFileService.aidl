package ru.rusinstaller;

// Runs inside the Shizuku (shell uid) process, which may access Android/data of other apps.
interface IFileService {
    void destroy() = 16777114;

    // entries "d|name" or "f|name|size"; null when path is not a readable directory
    String[] listDir(String path) = 1;
    // recursive, entries "d|rel/path" or "f|rel/path|size"
    String[] listTree(String root, int maxDepth, int maxEntries) = 2;
    boolean exists(String path) = 3;
    boolean isDir(String path) = 4;
    boolean mkdirs(String path) = 5;
    // copies everything from src into the file at path (overwrites); returns error text or null
    String writeFile(String path, in ParcelFileDescriptor src) = 6;
    ParcelFileDescriptor openRead(String path) = 7;
    boolean delete(String path) = 8;
}
