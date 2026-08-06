# Contributing

Thank you for helping improve Ecosystem Link.

## Before opening a change

1. Open an issue for substantial behavioral or protocol changes.
2. Keep Android and Python protocol models compatible.
3. Never commit real credentials, signing keys, VPN addresses, device identifiers, recordings, received files, or local configuration.
4. Preserve Android permission, foreground-service, and per-session consent boundaries.

## Development checks

```bash
cd arch-hub
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements-dev.txt
python -m pytest -q
python -m compileall -q ecosystem_hub tests
```

```bash
cd android-agent
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Hardware-facing changes should also be tested on a physical Android device. Include the Android version, device model, permission state, VPN type, reproduction steps, and sanitized logs in the pull request.

## Pull requests

Use a focused branch and describe the motivation, user impact, protocol impact, security/privacy impact, and validation performed. Add regression tests for fixes. By contributing, you agree that your contribution is licensed under the MIT License.

