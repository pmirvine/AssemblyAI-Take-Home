#!/bin/sh
# Run the fixed client (build.sh first). Needs a microphone and an API key:
#   export ASSEMBLYAI_API_KEY=your_key && ./run.sh
set -e
cd "$(dirname "$0")"
java -cp "out:lib/*" com.assemblyai.Spanglish
