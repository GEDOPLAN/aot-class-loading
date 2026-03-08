#!/usr/bin/env sh
# Linux/macOS-Wrapper (UTF-8 ist dort Standard, Flag schadet aber nicht)
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 benchmark.java