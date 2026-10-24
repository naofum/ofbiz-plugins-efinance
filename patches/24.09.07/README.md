# efinance rest-api patches

`efinance` needs three source changes in `plugins/rest-api`, which is outside
the efinance plugin. They are applied by the shared patch manager when efinance
is installed.

| Patch | Target | Purpose |
|---|---|---|
| `0001-rest-api-ef-auth-filter` | `EfAuthFilter.java` (new) | Validates efinance `/ef/` requests (token revocation and member lock) via `EfRequestValidator` reflection. |
| `0002-rest-api-public-auth` | `APIAuthFilter.java` | Allows REST operations declared with `auth="false"` (login, registration, product catalog, etc.). |
| `0003-rest-api-remote-ip` | `ServiceRequestHandler.java` | Uses the HTTP remote address for `ipAddress` so public simulation rate limiting cannot be spoofed by a request field. |

## Commands

```powershell
# Verify against the current working tree
.\gradlew.bat :plugins:efinance:verifyCorePatches

# Apply
.\gradlew.bat :plugins:efinance:applyCorePatches

# Roll back
.\gradlew.bat :plugins:efinance:rollbackCorePatches

# Clean-snapshot apply/rollback test
.\gradlew.bat :plugins:efinance:testCorePatch
```

`installPlugin -PpluginId=efinance` applies the patches. After applying source
patches, rebuild and restart OFBiz:

```powershell
.\gradlew.bat classes
```

Validation: `testCorePatch` passes, and `ofbiz --test component=efinance`
reports 94 tests, 0 errors, 0 failures with the patches applied.

## Baseline

`baseline/` contains the upstream `rest-api` files from
`apache/ofbiz-plugins` tag `release24.09.07`. They are **test fixtures only**;
the patch manager does not apply them. `testCorePatch` uses them when the
`plugins/rest-api` files are not tracked by the main Git repository.

## Notes

- `EfAuthFilter.java` is a new file; its manifest entry has an empty
  `sha256Before`, so the patch manager expects it to be absent before apply.
- `APIAuthFilter.java` and `ServiceRequestHandler.java` are diff patches
  against the upstream rest-api files, so upstream changes are preserved.
- Do not edit the three patched files by hand. Regenerate the patches from a
  clean rest-api baseline when upgrading.
