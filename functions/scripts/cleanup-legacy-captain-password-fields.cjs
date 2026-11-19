/**
 * One-time cleanup for legacy plaintext captain credential fields.
 *
 * Safe by default: this script only reports counts.
 * Apply the deletion only after the dashboard and app no longer write these fields:
 *   npm run migrate:remove-legacy-captain-passwords -- --apply
 *
 * Requires Google Application Default Credentials for project
 * el-batal-express-956ae. It never prints credential values.
 */
const admin = require("firebase-admin");

const PROJECT_ID = "el-batal-express-956ae";
const COLLECTIONS = ["captains", "drivers"];
const LEGACY_FIELDS = [
  "password",
  "captainPassword",
  "userPassword",
];
const APPLY = process.argv.includes("--apply");

async function main() {
  if (!admin.apps.length) {
    admin.initializeApp({
      credential: admin.credential.applicationDefault(),
      projectId: PROJECT_ID,
    });
  }

  const db = admin.firestore();
  const targetMap = new Map();
  const addTarget = (ref, fields) => {
    const existing = targetMap.get(ref.path);
    if (existing) {
      existing.fields = [...new Set([...existing.fields, ...fields])];
    } else {
      targetMap.set(ref.path, { ref, fields: [...new Set(fields)] });
    }
  };
  const totals = { captains: {}, drivers: {}, linkedUserProfiles: 0 };

  for (const collectionName of COLLECTIONS) {
    const snapshot = await db.collection(collectionName).get();
    let affected = 0;

    for (const document of snapshot.docs) {
      const fields = LEGACY_FIELDS.filter((field) =>
        Object.prototype.hasOwnProperty.call(document.data(), field)
      );

      if (fields.length > 0) {
        affected += 1;
        addTarget(document.ref, fields);
      }

      // The legacy dashboard also copied the password into users/{captainUid}.
      // Only inspect user profiles linked by the same Captain/Driver UID; never
      // scan unrelated customer or administrator profiles.
      const linkedUser = await db.collection("users").doc(document.id).get();
      if (linkedUser.exists) {
        const linkedFields = ["password", "captainPassword", "userPassword"].filter((field) =>
          Object.prototype.hasOwnProperty.call(linkedUser.data() || {}, field)
        );
        if (linkedFields.length > 0) {
          addTarget(linkedUser.ref, linkedFields);
          totals.linkedUserProfiles += 1;
        }
      }
    }

    totals[collectionName] = {
      scanned: snapshot.size,
      documentsWithLegacyCredentials: affected,
    };
  }

  const targets = Array.from(targetMap.values());

  process.stdout.write(
    JSON.stringify(
      {
        projectId: PROJECT_ID,
        mode: APPLY ? "APPLY" : "DRY_RUN",
        totals,
        documentsToUpdate: targets.length,
      },
      null,
      2
    ) + "\n"
  );

  if (!APPLY) {
    process.stdout.write(
      "Dry run only. No Firestore documents were changed. Re-run with --apply to remove only the listed legacy fields.\n"
    );
    return;
  }

  for (let offset = 0; offset < targets.length; offset += 400) {
    const batch = db.batch();
    const chunk = targets.slice(offset, offset + 400);

    for (const target of chunk) {
      const patch = {};
      for (const field of target.fields) {
        patch[field] = admin.firestore.FieldValue.delete();
      }
      batch.update(target.ref, patch);
    }

    await batch.commit();
    process.stdout.write(
      `Removed legacy credential fields from ${Math.min(offset + chunk.length, targets.length)} / ${targets.length} documents.\n`
    );
  }

  process.stdout.write("Legacy plaintext credential field cleanup completed.\n");
}

main().catch((error) => {
  process.stderr.write(
    `Cleanup failed: ${error && error.message ? error.message : String(error)}\n`
  );
  process.exitCode = 1;
});
