// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.events;

import java.nio.file.Path;

/** One file an edit rewrote: what it said, and what it says now. */
public record FileEdit(Path path, String previousContent, String newContent) {
}
