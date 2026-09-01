---
name: run-tests
description: Run all unit tests for the Pyramid Relay Android project. Use when the user asks to run tests, check test status, or verify code changes pass tests.
---

# Run Tests

Run all unit tests for the Pyramid Relay Android project.

## Command

```bash
./gradlew testDebugUnitTest
```

## Usage

Run the full unit test suite:

```bash
./gradlew testDebugUnitTest
```

This executes all tests under `app/src/test/java/com/pyramidrelay/` using Robolectric + MockK.

## Test Locations

- Unit tests: `app/src/test/java/com/pyramidrelay/`
- Test framework: Robolectric + MockK
- Configuration: `testOptions { unitTests.isReturnDefaultValues = true }`

## After Tests

- If tests pass: confirm all tests passed
- If tests fail: report which tests failed and show the failure details
