package com.botmaker.core.runtime;

import com.botmaker.runlog.RunLog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** A project's run folders, each the one a run kept its log in ({@link RunLog#FOLDER}). */
public final class RunFolders {

    private RunFolders() {
    }

    /** The project's runs that kept a log, newest first. */
    public static List<Path> runs(Path project) {
        try (Stream<Path> list = Files.list(project.resolve(RunLog.FOLDER))) {
            return list.filter(p -> Files.isRegularFile(p.resolve(RunLog.FILE)))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed()).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** The project's newest run, when one kept a log. */
    public static Optional<Path> latest(Path project) {
        return runs(project).stream().findFirst();
    }
}
