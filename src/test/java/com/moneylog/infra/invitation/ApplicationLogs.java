package com.moneylog.infra.invitation;

import java.util.List;

import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;

final class ApplicationLogs {

    private final Logger root;
    private final ListAppender<ILoggingEvent> appender;
    private final Level previousLevel;

    private ApplicationLogs(Logger root, ListAppender<ILoggingEvent> appender, Level previousLevel) {
        this.root = root;
        this.appender = appender;
        this.previousLevel = previousLevel;
    }

    static ApplicationLogs attach() {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Level previous = root.getLevel();
        root.addAppender(appender);
        return new ApplicationLogs(root, appender, previous);
    }

    void detach() {
        root.detachAppender(appender);
        appender.stop();
        root.setLevel(previousLevel);
    }

    List<String> messages() {
        return appender.list.stream().filter(event -> !event.getLoggerName().startsWith("org.springframework.test"))
                .map(event -> event.getFormattedMessage() + throwable(event.getThrowableProxy())).toList();
    }

    private static String throwable(IThrowableProxy proxy) {
        StringBuilder out = new StringBuilder();
        for (IThrowableProxy current = proxy; current != null; current = current.getCause()) {
            out.append(' ').append(current.getClassName()).append(':').append(current.getMessage());
        }
        return out.toString();
    }
}
