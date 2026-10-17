package com.github.catatafishen.agentbridge.ui;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

/**
 * Base for tests that instantiate a {@link NativeMarkdownPane} against the
 * platform fixture. Disposes the per-test conversation database after each
 * test so SQLite handles do not leak across tests, with a short grace period
 * for the disposal to settle before the fixture tears the project down.
 */
public abstract class NativeMarkdownPaneTestBase extends BasePlatformTestCase {

    @Override
    protected void tearDown() throws Exception {
        try {
            com.github.catatafishen.agentbridge.session.db.ConversationDatabase
                .getInstance(getProject()).dispose();
            Thread.sleep(100);
        } finally {
            super.tearDown();
        }
    }
}
