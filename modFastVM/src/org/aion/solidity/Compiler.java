package org.aion.solidity;

import static java.nio.file.Files.createTempDirectory;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class Compiler {

    private final File solc;

    private static Compiler instance;

    private static String helloAion =
            "pragma solidity ^0.4.15;\n" + "contract HelloAion {\n" + "\n" + "}";

    private Compiler() {
        solc = Paths.get("native", "linux", "solidity", "solc").toFile();
        solc.setExecutable(true);
    }

    public static synchronized Compiler getInstance() {
        if (instance == null) {
            instance = new Compiler();
        }
        return instance;
    }

    public Result compile(byte[] source, Options... options) throws IOException {
        return compile(source, false, true, options);
    }

    public Result compile(byte[] source, boolean optimize, boolean combinedJson, Options... options)
            throws IOException {

        List<String> commandParts = prepareCommands(optimize, combinedJson, options);

        ProcessBuilder processBuilder =
                new ProcessBuilder(commandParts).directory(solc.getParentFile());
        return runCompileProcess(source, processBuilder);
    }

    public Result compileZip(byte[] source, String entryPoint, Options... options)
            throws IOException {
        return compileZip(source, entryPoint, false, true, options);
    }

    public Result compileZip(
            byte[] source,
            String entryPoint,
            boolean optimize,
            boolean combinedJson,
            Options... options)
            throws IOException {

        if (source == null) return new Result("Missing source Zip file", "", true);

        Path unzipped = createTempDirectory("temp");
        extractZip(source, unzipped);

        if (unzipped == null) return new Result("Couldn't extract zip file", "", true);

        List<String> commandParts = prepareCommands(optimize, combinedJson, options);
        commandParts.add(entryPoint);

        ProcessBuilder processBuilder = new ProcessBuilder(commandParts).directory(unzipped.toFile());
        Result res = runCompileProcess(null, processBuilder);
        return res;
    }

    private List<String> prepareCommands(boolean optimize, boolean combinedJson, Options[] options)
            throws IOException {
        List<String> commandParts = new ArrayList<>();
        commandParts.add(solc.getCanonicalPath());

        if (optimize) {
            commandParts.add("--optimize");
        }
        if (combinedJson) {
            commandParts.add("--combined-json");
            commandParts.add(
                    Arrays.stream(options).map(o -> o.toString()).collect(Collectors.joining(",")));
        } else {
            for (Options option : options) {
                commandParts.add("--" + option.getName());
            }
        }
        return commandParts;
    }

    private Result runCompileProcess(byte[] source, ProcessBuilder processBuilder)
            throws IOException {
        processBuilder
                .environment()
                .put("LD_LIBRARY_PATH", solc.getParentFile().getCanonicalPath());

        Process process = processBuilder.start();

        if (source != null) {
            try (BufferedOutputStream stream =
                    new BufferedOutputStream(process.getOutputStream())) {
                stream.write(source);
            }
        }

        ParallelReader error = new ParallelReader(process.getErrorStream());
        ParallelReader output = new ParallelReader(process.getInputStream());
        error.start();
        output.start();

        try {
            boolean isFailed = process.waitFor() != 0;

            return new Result(error.getContent(), output.getContent(), isFailed);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private void extractZip(byte[] source, Path tempDir) throws IOException {

        ZipInputStream zipStream = new ZipInputStream(new ByteArrayInputStream(source));
        ZipEntry zipEntry = zipStream.getNextEntry();
        while (zipEntry != null) {
            File f = new File(tempDir.toFile(), zipEntry.getName());
            FileOutputStream fileOutputStream = new FileOutputStream(f);
            int len;
            byte[] buffer = new byte[1024];
            while ((len = zipStream.read(buffer)) > 0) {
                fileOutputStream.write(buffer, 0, len);
            }
            fileOutputStream.close();
            zipEntry = zipStream.getNextEntry();
        }
        zipStream.closeEntry();
        zipStream.close();
    }

    public Result compileHelloAion() throws IOException {
        return compile(helloAion.getBytes(), Compiler.Options.ABI, Compiler.Options.BIN);
    }

    public String getVersion() throws IOException {
        List<String> commandParts = new ArrayList<>();
        commandParts.add(solc.getCanonicalPath());
        commandParts.add("--version");

        ProcessBuilder processBuilder =
                new ProcessBuilder(commandParts).directory(solc.getParentFile());
        processBuilder
                .environment()
                .put("LD_LIBRARY_PATH", solc.getParentFile().getCanonicalPath());

        Process process = processBuilder.start();

        ParallelReader output = new ParallelReader(process.getInputStream());
        output.start();

        try {
            process.waitFor();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        return output.getContent();
    }

    public enum Options {
        AST("ast"),
        BIN("bin"),
        INTERFACE("interface"),
        ABI("abi");

        private final String name;

        Options(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    public static class Result {

        public String errors;
        public String output;

        @SuppressWarnings("unused")
        private boolean isFailed;

        public Result(String errors, String output) {
            this.errors = errors;
            this.output = output;
        }

        public Result(String errors, String output, boolean isFailed) {
            this.errors = errors;
            this.output = output;
            this.isFailed = isFailed;
        }

        public boolean isFailed() {
            return (errors != null && errors.contains("Error"));
        }
    }

    private static class ParallelReader extends Thread {

        private InputStream stream;
        private StringBuilder content = new StringBuilder();

        ParallelReader(InputStream stream) {
            this.stream = stream;
        }

        public String getContent() {
            return getContent(true);
        }

        public synchronized String getContent(boolean waitForComplete) {
            if (waitForComplete) {
                while (stream != null) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
            return content.toString();
        }

        public void run() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    content.append(line).append("\n");
                }
            } catch (IOException ioe) {
                ioe.printStackTrace();
            } finally {
                synchronized (this) {
                    stream = null;
                    notifyAll();
                }
            }
        }
    }
}
