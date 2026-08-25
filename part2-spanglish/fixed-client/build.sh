#!/bin/sh
# Compile the fixed Spanglish client against the bundled libraries.
# Requires JDK 11+ (verified with JDK 21). Output goes to ./out
set -e
cd "$(dirname "$0")"
javac -cp "lib/*" -d out Spanglish.java
echo "Compiled OK -> out/com/assemblyai/Spanglish.class"
