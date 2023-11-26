# LogFile
LogFile - Kotlin file wrapper to store logs with size restriction

When we write logs into a file, usually we will clear the whole file when it reaches the limit.

Added a wrapper around file to overcome this issue. It does these
* Retain the log contents as per the mentioned limit, write logs in a backup file before clearing it
* Append the log to avoid reading whole file into memory
* The final log file will have contents in reverse order (recent to old)
* Synchronization
