// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.project;

import java.nio.file.Path;
import java.time.LocalDateTime;

/**
 * Information about a project
 */
public record ProjectInfo(String name, Path projectPath, LocalDateTime lastModified){}
