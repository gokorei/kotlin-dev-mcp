## Summary

<!-- What does this PR change and why? -->

## Related tickets

<!-- Link to issues, e.g. "Closes #123". -->

## Test plan

- [ ] `./gradlew build` passes locally
- [ ] Tests added or adjusted for the change
- [ ] No machine-specific paths or secrets introduced
- [ ] README updated if user-facing behaviour changed (tools, resources, prompts)

## Checklist

- [ ] Diagnostics tools preserve the iterate-until-clean convention (`requireAnotherCall`)
- [ ] Stdio safety respected: no writes to stdout outside JSON-RPC frames (use `kotlin-logging` / stderr)
- [ ] New tools are atomic (no action discriminators) and read-only tools carry `readOnlyHint`
