@echo off
rem kmpzip.cmd — Windows wrapper that runs the native mingwX64 binary, building
rem it lazily on first use via gradlew. Mirrors the bash `kmpzip` wrapper.
setlocal
set SCRIPT_DIR=%~dp0
set BIN=%SCRIPT_DIR%kmp-zip-cli\build\bin\mingwX64\releaseExecutable\kmpzip-windows-x64.exe
if not exist "%BIN%" (
    echo Building native binary for mingwX64 (one-time)…>&2
    call "%SCRIPT_DIR%gradlew.bat" -q -p "%SCRIPT_DIR%" :kmp-zip-cli:linkReleaseExecutableMingwX64 || exit /b
)
"%BIN%" %*
