/*
 * Copyright 2020-2020 Exactpro (Exactpro Systems Limited)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.exactpro.th2.readlog.impl;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Objects;
import java.util.Queue;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.exactpro.th2.readlog.ILogReader;

public class DirectoryWatchLogReader implements ILogReader {
    private static final Logger LOGGER = LoggerFactory.getLogger(DirectoryWatchLogReader.class);
    private final File logDirectory;
    private final FilenameFilter filenameFilter;
    private final Queue<FileInfo> filesToProcess = new LinkedList<>();

    private FileInfo lastProcessedFile;
    private boolean canReturnLastString;
    private String lastReadString;
    private RandomAccessFile fileReader;

    public DirectoryWatchLogReader(File logDirectory, String fileFilterRegexp) throws FileNotFoundException {
        if (!logDirectory.exists()) {
            throw new FileNotFoundException("Cannot find directory: " + logDirectory);
        }
        if (logDirectory.isFile()) {
            throw new IllegalArgumentException("Expects that the " + logDirectory + " is a directory but it is a file");
        }
        this.logDirectory = Objects.requireNonNull(logDirectory, "'Log directory' parameter");
        Pattern fileFilterPattern = Pattern.compile(Objects.requireNonNull(fileFilterRegexp, "'File filter regexp' parameter"));
        filenameFilter = new RegexpFilenameFilter(fileFilterPattern);
        Queue<FileInfo> files = findFiles();
        LOGGER.info("Find {} files to precess. Files: {}", files == null ? 0 : files.size(), files);
        addFilesToQueue(files);
    }

    @Override
    @Nullable
    public String getNextLine() throws IOException {
        String nextLine = readNextLineSkipLast();
        if (nextLine != null) {
            return nextLine;
        }
        while (!filesToProcess.isEmpty()) {
            if (lastReadString != null) {
                return getLastAndUpdate(null);
            }
            initReaderForNextFile(filesToProcess.poll());
            String line = readNextLineSkipLast();
            if (line != null) {
                return line;
            }
        }
        // no files left
        // no lines read
        return null;
    }

    @Override
    public boolean refresh() throws IOException {
        LOGGER.debug("Refreshing state.");
        if (!filesToProcess.isEmpty()) {
            // no need to refresh. Some files left to process
            return true;
        }
        Queue<FileInfo> files = findFiles();
        if (files == null || files.isEmpty()) {
            LOGGER.debug("No new files found in the directory {}", logDirectory);
            return false;
        }
        Queue<FileInfo> filtered = filterLastWithSameTimeOrNewer(lastProcessedFile, files);
        LOGGER.debug("Filtered {} new or updated files. {}", filtered.size(), filtered);
        FileInfo firstFiltered = filtered.peek(); // might be the files with the same modification time or that is newer than the current one
        boolean differentFile = true;
        if (lastProcessedFile != null && lastProcessedFile.equals(firstFiltered)) {
            differentFile = false;
            if (isFileModified(lastProcessedFile, firstFiltered)) {
                long lastBeforePosition = lastProcessedFile.getPositionBeforeLastString();
                long lastAfterPosition = lastProcessedFile.getPositionAfterLastString();
                restoreLastPosition(lastBeforePosition);

                // actualize the position
                lastProcessedFile.setPositions(Long.MIN_VALUE, lastBeforePosition);
                String lastString = readNextLine();
                LOGGER.trace("Current last line: '{}', New last line: '{}'", lastReadString, lastString);
                if (Objects.equals(lastReadString, lastString)) {
                    canReturnLastString = true;
                    lastReadString = lastString;
                    FileInfo nextFile = filtered.poll();  // remove this file from queue because it is current now
                    //noinspection ConstantConditions
                    nextFile.copyPosition(lastProcessedFile); // we can be there only if 'nextFile' is not null
                    lastProcessedFile = nextFile;
                    addFilesToQueue(filtered);
                    return true;
                }
                lastReadString = lastString;

                // Last string is modified. That means we need to check if it is not a last line anymore
                String possibleLastString = readNextLine();
                if (possibleLastString == null) {
                    lastProcessedFile.setPositions(lastBeforePosition, lastAfterPosition);
                    return false;
                }
                LOGGER.trace("New line added to the file. Can return the last one: {}", lastReadString);
            } else {
                LOGGER.trace("File {} is not modified", lastProcessedFile.getPath());
                filtered.poll(); // remove first file from queue because it is the current one
            }
        }
        addFilesToQueue(filtered);
        canReturnLastString = lastReadString != null;
        return canReturnLastString || (!filtered.isEmpty() && differentFile);
    }

    private Queue<FileInfo> filterLastWithSameTimeOrNewer(@Nullable FileInfo lastProcessedFile, Queue<FileInfo> files) {
        if (files.isEmpty()) {
            return files;
        }
        if (lastProcessedFile == null) {
            return files;
        }
        boolean sameCreationTime = isSameModificationTime(lastProcessedFile, files.peek());
        if (sameCreationTime && files.size() == 1) {
            return files;
        }
        if (!sameCreationTime) {
            return files;
        }
        Queue<FileInfo> filtered = new LinkedList<>();
        Iterator<FileInfo> iterator = files.iterator();
        FileInfo prev = iterator.next();
        while (iterator.hasNext()) {
            FileInfo curr = iterator.next();
            if (!isSameModificationTime(lastProcessedFile, curr) && !lastProcessedFile.equals(curr)) {
                LOGGER.trace("Found first newer file. Add the previous one with same modification time {} and the new one {}", prev, curr);
                filtered.add(prev);
                filtered.add(curr);
                break;
            }
            prev = curr;
        }

        if (filtered.isEmpty()) {
            filtered.add(prev);
        } else {
            while (iterator.hasNext()) {
                filtered.add(iterator.next());
            }
        }

        return filtered;
    }

    private boolean isSameModificationTime(FileInfo current, FileInfo other) {
        LOGGER.trace("Compare {} and {}", current, other);
        return current.getLastModifiedTime().compareTo(other.getLastModifiedTime()) == 0;
    }

    @Override
    public void close() throws IOException {
        if (fileReader != null) {
            fileReader.close();
        }
    }

    private boolean isFileModified(FileInfo previousState, FileInfo currentState) {
        return !isSameModificationTime(previousState, currentState) || isDifferentSize(previousState, currentState);
    }

    private boolean isDifferentSize(FileInfo previousState, FileInfo currentState) {
        return previousState.getAttributes().size() != currentState.getAttributes().size();
    }

    private void initReaderForNextFile(FileInfo nextFile) throws IOException {
        if (nextFile == null) {
            LOGGER.warn("Try to init reader when no files in queue");
            return;
        }
        LOGGER.info("Start processing the file {}", nextFile);
        lastProcessedFile = nextFile;
        canReturnLastString = false;
        close();
        fileReader = createReader(nextFile);
        lastReadString = readNextLine();
    }

    private RandomAccessFile createReader(FileInfo nextFile) throws FileNotFoundException {
        return new RandomAccessFile(nextFile.getPath().toFile(), "r");
    }

    private void restoreLastPosition(long position) throws IOException {
        if (fileReader == null) {
            LOGGER.error("Cannot restore position because file reader is not set");
            return;
        }
        if (position > 0) {
            long actualLength = fileReader.length();
            if (actualLength < position) {
                throw new IllegalArgumentException("The actual file length " + actualLength + " is lower than requested position " + position);
            }
            fileReader = createReader(lastProcessedFile);
            fileReader.seek(position);
        }
    }

    /**
     * Keeps the last string from the file and returns null instead
     *
     * @return
     * @throws IOException
     */
    @Nullable
    private String readNextLineSkipLast() throws IOException {
        if (lastReadString != null && canReturnLastString) {
            canReturnLastString = false;
            return getLastAndUpdate(null);
        }
        String line = readNextLine();
        if (line != null) {
            return getLastAndUpdate(line);
        }
        return null;
    }

    private String getLastAndUpdate(String newValue) {
        String currentLast = lastReadString;
        lastReadString = newValue;
        return currentLast;
    }

    @Nullable
    private String readNextLine() throws IOException {
        if (fileReader == null) {
            return null;
        }
        String readLine = fileReader.readLine();
        if (readLine != null) {
            lastProcessedFile.updatePosition(fileReader.getFilePointer());
        }
        return readLine;
    }

    @Nullable
    private Queue<FileInfo> findFiles() {
        File[] files = logDirectory.listFiles(filenameFilter);
        if (files == null) {
            return null;
        }
        Instant lastProcessedFileCreationTime = lastProcessedFile == null ? null : lastProcessedFile.getLastModifiedTime();
        return Arrays.stream(files)
                .map(FileInfo::new)
                .filter(it -> lastProcessedFileCreationTime == null || it.getLastModifiedTime().compareTo(lastProcessedFileCreationTime) >= 0)
                .sorted(Comparator.comparing(FileInfo::getLastModifiedTime))
                .collect(Collectors.toCollection(LinkedList::new));
    }

    private void addFilesToQueue(@Nullable Queue<FileInfo> files) {
        if (files != null) {
            LOGGER.trace("Add files to queue: {}", files);
            filesToProcess.addAll(files);
        }
    }

    private static class RegexpFilenameFilter implements FilenameFilter {
        private final Pattern pattern;

        private RegexpFilenameFilter(Pattern pattern) {
            this.pattern = Objects.requireNonNull(pattern, "'Pattern' parameter");
        }

        @Override
        public boolean accept(File dir, String name) {
            return pattern.matcher(name).matches();
        }
    }

    private static class FileInfo {
        private final Path path;
        private final BasicFileAttributes attributes;
        private final Instant lastModifiedTime;
        private long positionBefore = Long.MIN_VALUE;
        private long positionAfter = Long.MIN_VALUE;

        private FileInfo(File file) {
            this.path = file.toPath();
            this.attributes = extractAttributes(path);
            lastModifiedTime = attributes.lastModifiedTime().toInstant();
        }

        public Path getPath() {
            return path;
        }

        public BasicFileAttributes getAttributes() {
            return attributes;
        }

        public Instant getLastModifiedTime() {
            return lastModifiedTime;
        }

        public long getPositionBeforeLastString() {
            return positionBefore;
        }

        public long getPositionAfterLastString() {
            return positionAfter;
        }

        public void setPositions(long before, long after) {
            positionBefore = before;
            positionAfter = after;
        }

        public void copyPosition(FileInfo other) {
            positionBefore = other.positionBefore;
            positionAfter = other.positionAfter;
        }

        public void updatePosition(long position) {
            positionBefore = positionAfter;
            positionAfter = position;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;

            FileInfo fileInfo = (FileInfo)o;

            return Objects.equals(path, fileInfo.path);
        }

        @Override
        public int hashCode() {
            return path == null ? 0 : path.hashCode();
        }

        @Override
        public String toString() {
            return new ToStringBuilder(this, ToStringStyle.NO_CLASS_NAME_STYLE)
                    .append("path", path)
                    .append("lastModifiedTime", lastModifiedTime)
                    .append("size", attributes.size())
                    .toString();
        }

        private static BasicFileAttributes extractAttributes(Path path) {
            try {
                return Files.readAttributes(path, BasicFileAttributes.class);
            } catch (IOException e) {
                throw new RuntimeException("Cannot extract basic attributes", e);
            }
        }
    }
}