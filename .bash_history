pkg update && pkg upgrade -y
pkg install -y git openssh
cd ~ && ls -la
ls /sdcard/
cd "/storage/emulated/0/new P/MyIDE"
pwd && ls -la
find . -maxdepth 3 -type f | head -100
ls -la app
ls -la .github
ls -la .github/workflows 2>/dev/null
cat "/storage/emulated/0/new P/MyIDE/settings.gradle" 2>&1 | head -5
termux-setup-storage
ls -la ~/storage/
ls -la ~/storage/shared/
ls -la "/storage/emulated/0/new P/MyIDE/"
ls -la "/storage/emulated/0/new P/MyIDE/app/"
ls -la "/storage/emulated/0/new P/MyIDE/app/src/"
ls -la "/storage/emulated/0/new P/MyIDE/app/src/main/"
du -sh "/storage/emulated/0/new P/MyIDE/app/src/main/assets/"*
ls -laR "/storage/emulated/0/new P/MyIDE/app/src/main/jniLibs/"
ls -la "/storage/emulated/0/new P/MyIDE/app/src/main/assets/jniLibs/"
ls -la "/storage/emulated/0/new P/MyIDE/app/src/main/assets/keys/"
find "/storage/emulated/0/new P/MyIDE/app/src/main/java" -type f
ls -R "/storage/emulated/0/new P/MyIDE/app/src/main/res/"
cd "/storage/emulated/0/new P/MyIDE" && git status
git config --global --add safe.directory "/storage/emulated/0/new P/MyIDE"
git status
git remote -v
termux-clipboard-get
pkg install -y termux-api
termux-clipboard-get
cd "/storage/emulated/0/new P/MyIDE"
git remote set-url origin https://github.com/fahim-git-00/myide-buildtools.git
git remote -v
git config --global credential.helper store
git push
ls -la "/storage/emulated/0/tok.txt" 2>/dev/null || ls -la "/storage/emulated/0/main storage/tok.txt" 2>/dev/null || find /sdcard -maxdepth 3 -name "tok.txt" 2>/dev/null
cd "/storage/emulated/0/new P/MyIDE"
git push https://fahim-git-00:$(cat /storage/emulated/0/tok.txt)@github.com/fahim-git-00/myide-buildtools.git main
git pull https://fahim-git-00:$(cat /storage/emulated/0/tok.txt)@github.com/fahim-git-00/myide-buildtools.git main
git push https://fahim-git-00:$(cat /storage/emulated/0/tok.txt)@github.com/fahim-git-00/myide-buildtools.git main
git ls-files | head -50
git ls-files | wc -l
cat ".github/workflows/build.yml"
cat "/storage/emulated/0/newshit00/TEST KEY/.myide/deps.txt" 2>/dev/null; echo "---"; ls -la "/data/data/com.fahim.myide/cache/myide-m2/com/squareup/okhttp3/" 2>/dev/null || echo "no okhttp cache"
head -50 "/storage/emulated/0/new P/MyIDE/app/src/main/java/com/fahim/myide/ApkBuilder.java"
grep -n "autoResolveDeps" "/storage/emulated/0/new P/MyIDE/app/src/main/java/com/fahim/myide/ApkBuilder.java"
cd "/storage/emulated/0/new P/MyIDE" && git add -A && git status
git commit -m "Fix: auto-resolve Maven deps when cache is cold"
git config --global user.name "fahim-git-00"
git config --global user.email "fahim-git-00@users.noreply.github.com"
git commit -m "Fix: auto-resolve Maven deps when cache is cold"
git push https://fahim-git-00:$(cat /storage/emulated/0/tok.txt)@github.com/fahim-git-00/myide-buildtools.git main
ls -la "/data/data/com.fahim.myide/cache/myide-m2/com/squareup/okhttp3/okhttp/4.12.0/" 2>/dev/null
ls -la "/data/data/com.fahim.myide/cache/myide-m2/org/jetbrains/kotlin/kotlin-stdlib/1.9.10/" 2>/dev/null
cd "/storage/emulated/0/new P/MyIDE"
git add -A
git status
git commit -m "Use D8 instead of R8; fix jar inputs"
git push https://fahim-git-00:$(cat /storage/emulated/0/tok.txt)@github.com/fahim-git-00/myide-buildtools.git main
pwd
git remote -v
git branch --show-current
git log --oneline -5
git status
ls -la
cat .github/workflows/*.yml
cat gradle/wrapper/gradle-wrapper.properties
cat app/build.gradle
cat build.gradle
ls -la app/
ls -la app/src/
ls -la app/src/main/
ls -la app/src/main/res/ 2>/dev/null
ls -la app/libs/
ls -la gradle/ 2>/dev/null
ls -la app/src/main/jniLibs/
ls -la app/src/main/assets/
cat app/src/main/res/values/*.xml
org.gradle.jvmargs=-Xmx2048m -XX:MaxMetaspaceSize=512m
android.useAndroidX=true
android.enableJetifier=false
org.gradle.caching=true
org.gradle.parallel=true
ls -la app/src/main/res/values-night/
ls -la app/src/main/res/values-v21/
ls -la app/src/main/res/xml/
ls -la app/src/main/res/menu/
ls -la gradle.properties
git ls-files | grep gradle.properties
cat .gitignore
mkdir -p .github/workflows
nano .github/workflows/kotlin-compile.yml
cat .github/workflows/kotlin-compile.yml
cd app/src/main/assets
wget https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-compiler-embeddable/1.9.24/kotlin-compiler-embeddable-1.9.24.jar
wget https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/1.9.24/kotlin-stdlib-1.9.24.jar
ls -la *.jar
pkg install wget -y
wget https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-compiler-embeddable/1.9.24/kotlin-compiler-embeddable-1.9.24.jar
wget https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/1.9.24/kotlin-stdlib-1.9.24.jar
ls -la *.jar
pkg install git-lfs -y
git lfs install
git lfs track "app/src/main/assets/*.jar"
git add .gitattributes
git add app/src/main/assets/*.jar
git commit -m "Add Kotlin compiler + stdlib jars"
git push
cat .gitattributes
git lfs ls-files
cd ../../..
pwd
git status
cd ..
pwd
git status
git add -A
git lfs ls-files
git commit -m "Add Kotlin support (local + remote)"
git push
git add -A
git commit -m "Fix chunk_count input type"
git push
cd "/storage/emulated/0/new P/MyIDE"
nano .github/workflows/kotlin-compile.yml
cd "/storage/emulated/0/new P/MyIDE"
git add .github/workflows/kotlin-compile.yml
git commit -m "Fix decode step: print debug + empty-safe"
git push
cd "/storage/emulated/0/new P/MyIDE"
git status
git log --oneline -3
cd "/storage/emulated/0/new P/MyIDE"
git add -A
git commit -m "Use safe separators for remote payload"
git push
git add -A
git commit -m "Add stdlib to kotlinc classpath"
git push
cd "/storage/emulated/0/new P/MyIDE"
git add -A
git commit -m "Batch 1: editor polish (auto-close, indent, tab, shortcuts)"
git push
