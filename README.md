# whisper.clj

Clojure wrapper for whisper.cpp. Transcribe audio.

## Usage

### 1. Build whisper.cpp

```bash
git clone https://github.com/ggerganov/whisper.cpp.git
cmake -B build -DBUILD_SHARED_LIBS=1 -DGGML_USE_ACCELERATE=1 -DGGML_USE_METAL=1 -DGGML_METAL_EMBED_LIBRARY=1
cmake --build build -j --config Release
```

### 2. Download model

```bash
# in whisper.cpp
bash ./models/download-ggml-model.sh base.en
```

### 3. Copy model to whisper.clj

```bash
# in whisper.clj
mkdir models
cp ../whisper.cpp/models/ggml-base.en.bin models/
```

### 4. Setup alias

Create alias to point to local build

```clojure
:whisper
{:jvm-opts ["-Djna.library.path=../whisper.cpp/build/src/"]}
```

### 5. Transcribe Audio

```clojure

(require '[com.phronemophobic.whisper :as whisper])

;; start recording
(def get-text (record-and-transcribe "models/ggml-base.en.bin"))

;; stop recording and return transcription
(def transcription (get-text))
```

If it didn't work, you may have to check your OSX permissions and allow microphone access to Terminal.

## License

The MIT License (MIT)

Copyright © 2024 Adrian Smith

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the “Software”), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED “AS IS”, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.



