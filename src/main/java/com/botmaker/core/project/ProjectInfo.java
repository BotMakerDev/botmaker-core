package com.botmaker.core.project;

import java.nio.file.Path;
import java.time.LocalDateTime;

/**
 * Information about a project
 */
public record ProjectInfo(String name, Path projectPath, LocalDateTime lastModified){}
