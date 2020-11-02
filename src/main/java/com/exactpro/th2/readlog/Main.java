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

package com.exactpro.th2.readlog;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.exactpro.th2.common.metrics.CommonMetrics;
import com.exactpro.th2.common.schema.factory.CommonFactory;
import com.exactpro.th2.readlog.cfg.LogReaderConfiguration;

public class Main extends Object  {

	private static final Logger logger = LoggerFactory.getLogger(Main.class);

	public static void main(String[] args) {
        Deque<AutoCloseable> toDispose = new ArrayDeque<>();

        // Probably we should not use shutdown hook in application that does everything in the Main thread.
        // But I believe that it will be changed soon the and reader will become multithreading-reader.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> closeResources(toDispose), "Shutdown hook"));

        CommonMetrics.setLiveness(true);
        CommonFactory commonFactory = CommonFactory.createFromArguments(args);
        toDispose.add(commonFactory);

        Properties props = new Properties();

	    ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

	    try (InputStream configStream = classLoader.getResourceAsStream("logback.xml"))  {
	        props.load(configStream);
	    } catch (IOException e) {
	        logger.error("Error: can't load log configuration file", e);
	    }

        LogReaderConfiguration configuration = commonFactory.getCustomConfiguration(LogReaderConfiguration.class);

        File logFile = configuration.getLogFile();
        LogPublisher publisher = new LogPublisher(logFile.getName(), commonFactory.getMessageRouterRawBatch());
        toDispose.add(publisher);

		LogReader reader = new LogReader(logFile);
		toDispose.add(reader);

		RegexLogParser logParser = new RegexLogParser(configuration.getRegexp(), configuration.getRegexpGroups());
		CommonMetrics.setReadiness(true);

		try {
			while (true) {
				String line = reader.getNextLine();

				if (line != null) {
					List<String> parsedLines = logParser.parse(line);
					for (String parsedLine: parsedLines) {
						publisher.publish(parsedLine);
					}
				} else {
					long linesCount = reader.getLineCount();

					long processedLinesCount = reader.getProcessedLinesCount();

					if (linesCount > processedLinesCount) {
						reader.close();
						reader.open();
						reader.skip(processedLinesCount);
					} else if (linesCount < processedLinesCount) {
						reader.close();
						reader.open();
					} else {
						Thread.sleep(5000);
					}
				}
			}

		} catch (IOException| InterruptedException e) {
			logger.error("Cannot read log file: {}", logFile, e);
		}
	}

    private static void closeResources(Deque<AutoCloseable> toDispose) {
        CommonMetrics.setReadiness(false);
        toDispose.descendingIterator().forEachRemaining(resource -> {
            try {
                resource.close();
            } catch (Exception e) {
                logger.error("Cannot close resource", e);
            }
        });
        CommonMetrics.setLiveness(false);
    }
}
