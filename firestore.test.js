const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function validConversation(uid, overrides = {}) {
  const past = new Date(Date.now() - 5000);
  return {
    userId: uid,
    title: "Explore Kotlin Coroutines",
    personaId: "general",
    modelName: "gemini-3.8-flash",
    lastMessagePreview: "How do structured coroutines work?",
    messageCount: 1,
    createdAt: past,
    updatedAt: past,
    ...overrides,
  };
}

function validMessage(uid, conversationId, overrides = {}) {
  const past = new Date(Date.now() - 5000);
  return {
    userId: uid,
    conversationId,
    role: "user",
    text: "Explain Android 8 notification channels",
    hasImage: false,
    createdAt: past,
    ...overrides,
  };
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user: cannot list or write conversations", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("conversations").get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("conversations").doc("conv_1").set(
      validConversation(ALICE_UID)
    )
  );
});

test("Authenticated owner: can create, read, list, update, and delete own conversation", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const convRef = aliceDb.collection("users").doc(ALICE_UID).collection("conversations").doc("conv_1");

  await assertSucceeds(convRef.set(validConversation(ALICE_UID)));
  await assertSucceeds(convRef.get());
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("conversations").get());

  await assertSucceeds(
    convRef.update({
      lastMessagePreview: "Updated answer preview",
      messageCount: 2,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  await assertSucceeds(convRef.delete());
});

test("Cross-user isolation: Bob cannot read, list, create, update, or delete Alice's conversation", async () => {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection("users").doc(ALICE_UID).collection("conversations").doc("conv_1").set(
      validConversation(ALICE_UID)
    );
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const aliceConv = bobDb.collection("users").doc(ALICE_UID).collection("conversations").doc("conv_1");

  await assertFails(aliceConv.get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("conversations").get());
  await assertFails(aliceConv.update({ title: "Hijacked" }));
  await assertFails(aliceConv.delete());
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("conversations").doc("conv_2").set(
      validConversation(BOB_UID)
    )
  );
});

test("Dirty Dozen: rejects ghost fields, invalid persona/model, oversized strings, and spoofed userId", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const convCol = aliceDb.collection("users").doc(ALICE_UID).collection("conversations");

  // Ghost field on create
  await assertFails(convCol.doc("c_ghost").set({ ...validConversation(ALICE_UID), isAdmin: true }));

  // Invalid personaId
  await assertFails(convCol.doc("c_persona").set(validConversation(ALICE_UID, { personaId: "hacker" })));

  // Invalid modelName
  await assertFails(convCol.doc("c_model").set(validConversation(ALICE_UID, { modelName: "gpt-4" })));

  // Oversized title
  await assertFails(convCol.doc("c_long").set(validConversation(ALICE_UID, { title: "a".repeat(121) })));

  // Spoofed userId
  await assertFails(convCol.doc("c_spoof").set(validConversation(BOB_UID)));

  // Invalid document ID
  await assertFails(convCol.doc("bad id with spaces!").set(validConversation(ALICE_UID)));
});

test("Messages: owner can batch-create conversation + message, cannot create orphan or update message", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const convRef = aliceDb.collection("users").doc(ALICE_UID).collection("conversations").doc("conv_batch");
  const msgRef = convRef.collection("messages").doc("msg_1");

  // Orphaned message without parent conversation fails
  await assertFails(msgRef.set(validMessage(ALICE_UID, "conv_batch")));

  // Atomic batch creating conversation + message succeeds
  const batch = aliceDb.batch();
  batch.set(convRef, validConversation(ALICE_UID));
  batch.set(msgRef, validMessage(ALICE_UID, "conv_batch", { modelUsed: "gemini-4-argon" }));
  await assertSucceeds(batch.commit());

  // Reading & listing messages succeeds for Alice
  await assertSucceeds(msgRef.get());
  await assertSucceeds(convRef.collection("messages").get());

  // Messages are immutable (update rejected)
  await assertFails(msgRef.update({ text: "Tampered" }));

  // Cross-user read fails for Bob
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("conversations").doc("conv_batch").collection("messages").get()
  );
});
