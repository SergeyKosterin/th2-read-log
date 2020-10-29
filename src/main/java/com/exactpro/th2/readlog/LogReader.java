package com.exactpro.th2.readlog;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.logstash.logback.argument.StructuredArguments;

public class LogReader implements AutoCloseable {
	private String fileName;
	private BufferedReader reader;
	private Logger logger = LoggerFactory.getLogger(LogReader.class);
	
	private boolean closeState;
	private long processedLinesCount;
	
	public LogReader() {
		this.fileName = System.getenv("LOG_FILE_NAME");
		open();
	}
	
	public void open() {
		closeState = false;
		
		try {
			reader = new BufferedReader(new FileReader(fileName));
		} catch (FileNotFoundException e) {
			logger.error("{}", e.getMessage(), StructuredArguments.value("stacktrace",e.getStackTrace()), e);			
		}
		
		logger.info("Open log file", StructuredArguments.value("fileName",fileName));
		processedLinesCount = 0;		
	}
	
	public long getLineCount() throws IOException {

	    try (Stream<String> lines = Files.lines(new File(fileName).toPath())) {
	        return lines.count();
	    }
	}
	
	public void skip(long lineNumber) throws IOException {		
		logger.trace("Skipping",StructuredArguments.value("LinesToSkip",lineNumber));
		
		for (long i=0; i<lineNumber; ++i) {
			reader.readLine();
			++processedLinesCount;
		}
	}
	
	public long getProcessedLinesCount() {
		return processedLinesCount;
	}
	
	public String getNextLine() throws IOException {
		String result = reader.readLine();
		logger.trace("RawLogLine",StructuredArguments.value("RawLogLine",result));
		if (result != null) {
			++processedLinesCount;
		}
		return result;
	}
	
	public String getFileName() {
		return fileName;
	}
	
	
	public boolean isClosed() {
		return closeState;
	}
	
	@Override
	public void close() throws IOException {
		if (reader != null) {
			reader.close();
			closeState=true;
		}
		logger.info("Close log file", StructuredArguments.value("fileName",fileName));
	}
}
