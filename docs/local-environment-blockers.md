# Local environment setup and resolved blockers

Initially observed on 2026-07-29:

- `python` resolves to an inaccessible Microsoft Store application alias; `py` is absent.
- `git` is absent from `PATH`.
- WSL is not installed.
- Gradle 8.10.2 with Android Studio's JBR starts, but Maven Central resolution fails with `PKIX path building failed` / `certificate_unknown` while downloading `kotlin-reflect:1.9.24`. Offline mode also fails because that artifact is not cached.

Likely PKIX causes are an intercepting proxy/antivirus certificate absent from the JBR trust store, an incorrect system clock, or a damaged/outdated JBR CA bundle. Check the clock and HTTPS inspection policy first. Prefer updating Android Studio/JBR or configuring the organisation's documented Gradle proxy and CA procedure. Do not disable TLS verification and do not import an unverified certificate. This project does not modify the machine trust store.

Install current CPython from python.org or `winget install Python.Python.3.12`, disable the conflicting Store aliases if necessary, then verify with `python --version` and `python -m pip --version`. Install Git with `winget install Git.Git`, open a new terminal, and verify with `git --version`. Optional WSL setup from an elevated terminal is `wsl --install`; reboot and verify with `wsl --status`.

After remediation:

```powershell
cd arch-hub
python -m pip install -r requirements-dev.txt
python -m pytest -q

cd ..\android-agent
.\gradlew.bat testDebugUnitTest assembleDebug --no-daemon
```

## Resolution applied

- Installed Python 3.12.10 using Windows Package Manager and created `arch-hub/.venv`.
- Installed the declared Python dependencies; the hub suite passes with 24 tests.
- Repaired/upgraded Git for Windows to 2.55.0.windows.3.
- Installed Microsoft OpenJDK 17.0.20, configured user-level `JAVA_HOME`, and confirmed both it and Android Studio's bundled JBR can build with the installed Android SDK.
- Diagnosed Windows certificate revocation lookup failure (`CRYPT_E_NO_REVOCATION_CHECK`) and the JBR PKIX failure. The system trust store was not modified and TLS verification was not disabled.
- Downloaded the single missing pinned artifact, `kotlin-reflect:1.9.24`, plus its POM into `android-agent/local-maven` using Schannel certificate validation with only the unavailable revocation check skipped. Its SHA-1 was verified as `767f8e3d382a98e2d5a465abe36be2b7019a7be4`, matching Maven Central's response metadata.
- Generated the standard Gradle 8.7 wrapper. Android tests and `assembleDebug` pass through `gradlew.bat` in offline mode.

Use these verified commands:

```powershell
cd arch-hub
.\.venv\Scripts\python.exe -m pytest -q

cd ..\android-agent
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-17.0.20.8-hotspot'
.\gradlew.bat testDebugUnitTest assembleDebug --offline --no-daemon
```
