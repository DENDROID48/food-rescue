package com.foodrescue;

import com.foodrescue.exception.InvalidLaunchOptionException;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Short program arguments, checked and turned into the Spring settings they stand for.
 * Any other argument (for example {@code --spring.profiles.active=postgres}) is passed on unchanged.
 *
 * @param springArguments arguments to hand to Spring
 * @param helpRequested   true when {@code --help} was given
 */
public record LaunchOptions(List<String> springArguments, boolean helpRequested) {

    public static final String USAGE = """
            Usage: java -jar food-rescue.jar [options]
              --mode=demo        demo data, kept in memory and lost when the program stops (default)
              --mode=files       keep the data in CSV files and read them back on the next start
              --data-dir=PATH    folder for the data files (default: data)
              --port=NUMBER      web server port, 1-65535 (default: 8080)
              --help             show this text
            Spring settings also work, for example --spring.profiles.active=postgres""";

    public static LaunchOptions parse(String... args) {
        List<String> springArguments = new ArrayList<>();
        boolean help = false;
        for (String arg : args) {
            if (arg.equals("--help") || arg.equals("-h")) {
                help = true;
            } else if (arg.startsWith("--mode=")) {
                springArguments.add("--foodrescue.storage.mode=" + parseMode(valueOf(arg)));
            } else if (arg.startsWith("--data-dir=")) {
                springArguments.add("--foodrescue.storage.data-dir=" + parseDataDir(valueOf(arg)));
            } else if (arg.startsWith("--port=")) {
                springArguments.add("--server.port=" + parsePort(valueOf(arg)));
            } else if (arg.equals("--mode") || arg.equals("--data-dir") || arg.equals("--port")) {
                throw new InvalidLaunchOptionException("Write " + arg + "=VALUE, with an equals sign");
            } else {
                springArguments.add(arg);
            }
        }
        return new LaunchOptions(List.copyOf(springArguments), help);
    }

    private static String valueOf(String arg) {
        return arg.substring(arg.indexOf('=') + 1).trim();
    }

    static String parseMode(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "demo", "memory" -> "memory";
            case "files", "file" -> "files";
            default -> throw new InvalidLaunchOptionException(
                    "Unknown mode '" + value + "'. Use --mode=demo or --mode=files");
        };
    }

    static int parsePort(String value) {
        int port;
        try {
            port = Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            throw new InvalidLaunchOptionException("--port must be a whole number, not '" + value + "'", ex);
        }
        if (port < 1 || port > 65535) {
            throw new InvalidLaunchOptionException("--port must be between 1 and 65535, not " + port);
        }
        return port;
    }

    static String parseDataDir(String value) {
        if (value.isBlank()) {
            throw new InvalidLaunchOptionException("--data-dir needs a folder, for example --data-dir=data");
        }
        try {
            Path.of(value);
        } catch (InvalidPathException ex) {
            throw new InvalidLaunchOptionException("--data-dir is not a valid folder name: " + ex.getReason(), ex);
        }
        return value;
    }
}
