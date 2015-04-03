package com.jetbrains.python.debugger.pydev;

import com.intellij.openapi.util.text.StringUtil;
import com.jetbrains.python.debugger.*;
import com.thoughtworks.xstream.io.naming.NoNameCoder;
import com.thoughtworks.xstream.io.xml.XppReader;
import org.jetbrains.annotations.NotNull;
import org.xmlpull.mxp1.MXParser;

import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.LinkedList;
import java.util.List;


public class ProtocolParser {

  private ProtocolParser() {
  }

  public static PySignature parseCallSignature(String payload) throws PyDebuggerException {
    final XppReader reader = openReader(payload, true);
    reader.moveDown();
    if (!"call_signature".equals(reader.getNodeName())) {
      throw new PyDebuggerException("Expected <call_signature>, found " + reader.getNodeName());
    }
    final String file = readString(reader, "file", "");
    final String name = readString(reader, "name", "");
    PySignature signature = new PySignature(file, name);

    while (reader.hasMoreChildren()) {
      reader.moveDown();
      if (!"arg".equals(reader.getNodeName())) {
        throw new PyDebuggerException("Expected <arg>, found " + reader.getNodeName());
      }
      signature.addArgument(readString(reader, "name", ""), readString(reader, "type", ""));
      reader.moveUp();
    }

    return signature;
  }

  public static PyThreadingEvent parseThreadingEvent(String payload,
                                                     final PyPositionConverter positionConverter) throws PyDebuggerException {
    final XppReader reader = openReader(payload, true);
    reader.moveDown();
    if (!"threading_event".equals(reader.getNodeName())) {
      throw new PyDebuggerException("Expected <threading_event>, found " + reader.getNodeName());
    }
    final Integer time = Integer.parseInt(readString(reader, "time", ""));
    final String name = readString(reader, "name", "");
    final String thread_id = readString(reader, "thread_id", "");
    final String type = readString(reader, "type", "");
    PyThreadingEvent threadingEvent;
    if (type.equals("lock")) {
      String lock_id = readString(reader, "lock_id", "0");
      threadingEvent = new PyLockEvent(time, thread_id, name, lock_id);
    } else if (type.equals("thread")) {
      threadingEvent = new PyThreadEvent(time, thread_id, name);
    } else {
      throw new PyDebuggerException("Unknown type " + type);
    }

    final String event = readString(reader, "event", "");
    if (event.equals("__init__")) {
      threadingEvent.setType(PyThreadingEvent.EventType.CREATE);
    } else if (event.equals("start")) {
      threadingEvent.setType(PyThreadingEvent.EventType.START);
    } else if (event.equals("join")) {
      threadingEvent.setType(PyThreadingEvent.EventType.JOIN);
    } else if (event.equals("stop")) {
      threadingEvent.setType(PyThreadingEvent.EventType.STOP);
    } else if (event.equals("acquire_begin") || event.equals("__enter___begin")) {
      threadingEvent.setType(PyThreadingEvent.EventType.ACQUIRE_BEGIN);
    }  else if (event.equals("acquire_end") || event.equals("__enter___end")) {
        threadingEvent.setType(PyThreadingEvent.EventType.ACQUIRE_END);
    } else if (event.startsWith("release") || event.startsWith("__exit__")) {
      // we record release begin and end on the Python side, but it is not important info
      // for user. Maybe use it later
      threadingEvent.setType(PyThreadingEvent.EventType.RELEASE);
    } else {
      throw new PyDebuggerException("Unknown event " + event);
    }

    threadingEvent.setFileName(readString(reader, "file", ""));
    threadingEvent.setLine(Integer.parseInt(readString(reader, "line", "")) - 1);
    reader.moveUp();

    final List<PyStackFrameInfo> frames = new LinkedList<PyStackFrameInfo>();
    while (reader.hasMoreChildren()) {
      reader.moveDown();
      frames.add(parseFrame(reader, thread_id, positionConverter));
      reader.moveUp();
    }
    threadingEvent.setFrames(frames);

    return threadingEvent;
  }

  public static String parseSourceContent(String payload) throws PyDebuggerException {
    return payload;
  }

  public static String decode(final String value) throws PyDebuggerException {
    try {
      return URLDecoder.decode(value, "UTF-8");
    }
    catch (UnsupportedEncodingException e) {
      throw new PyDebuggerException("Unable to decode: " + value + ", reason: " + e.getMessage());
    }
  }

  public static String encodeExpression(final String expression) {
    return StringUtil.replace(expression, "\n", "@LINE@");
  }

  public static PyIo parseIo(final String text) throws PyDebuggerException {
    final XppReader reader = openReader(text, true);
    reader.moveDown();
    if (!"io".equals(reader.getNodeName())) {
      throw new PyDebuggerException("Expected <io>, found " + reader.getNodeName());
    }
    final String s = readString(reader, "s", "");
    final int ctx = readInt(reader, "ctx", 1);
    return new PyIo(s, ctx);
  }

  @NotNull
  public static PyThreadInfo parseThread(final String text, final PyPositionConverter positionConverter) throws PyDebuggerException {
    final XppReader reader = openReader(text, true);
    reader.moveDown();
    if (!"thread".equals(reader.getNodeName())) {
      throw new PyDebuggerException("Expected <thread>, found " + reader.getNodeName());
    }

    final String id = readString(reader, "id", null);
    final String name = readString(reader, "name", "");
    final int stopReason = readInt(reader, "stop_reason", 0);
    String message = readString(reader, "message", "None");
    if ("None".equals(message)) {
      message = null;
    }

    final List<PyStackFrameInfo> frames = new LinkedList<PyStackFrameInfo>();
    while (reader.hasMoreChildren()) {
      reader.moveDown();
      frames.add(parseFrame(reader, id, positionConverter));
      reader.moveUp();
    }

    return new PyThreadInfo(id, name, frames, stopReason, message);
  }

  @NotNull
  public static String getThreadId(@NotNull String payload) {
    return payload.split("\t")[0];
  }

  private static PyStackFrameInfo parseFrame(final XppReader reader, final String threadId, final PyPositionConverter positionConverter)
    throws PyDebuggerException {
    if (!"frame".equals(reader.getNodeName())) {
      throw new PyDebuggerException("Expected <frame>, found " + reader.getNodeName());
    }

    final String id = readString(reader, "id", null);
    final String name = readString(reader, "name", null);
    final String file = readString(reader, "file", null);
    final int line = readInt(reader, "line", 0);

    return new PyStackFrameInfo(threadId, id, name, positionConverter.create(file, line));
  }

  @NotNull
  public static PyDebugValue parseValue(final String text, final PyFrameAccessor frameAccessor) throws PyDebuggerException {
    final XppReader reader = openReader(text, true);
    reader.moveDown();
    return parseValue(reader, frameAccessor);
  }

  @NotNull
  public static List<PyDebugValue> parseReferrers(final String text, final PyFrameAccessor frameAccessor) throws PyDebuggerException {
    final List<PyDebugValue> values = new LinkedList<PyDebugValue>();

    final XppReader reader = openReader(text, false);

    while (reader.hasMoreChildren()) {
      reader.moveDown();
      if (reader.getNodeName().equals("var")) {
        PyDebugValue value = parseValue(reader, frameAccessor);
        value.setId(readString(reader, "id", null));
        values.add(value);
      }
      else if (reader.getNodeName().equals("for")) {
        //TODO
      }
      else {
        throw new PyDebuggerException("Expected <var> or <for>, found " + reader.getNodeName());
      }
      reader.moveUp();
    }

    return values;
  }


  @NotNull
  public static List<PyDebugValue> parseValues(final String text, final PyFrameAccessor frameAccessor) throws PyDebuggerException {
    final List<PyDebugValue> values = new LinkedList<PyDebugValue>();

    final XppReader reader = openReader(text, false);
    while (reader.hasMoreChildren()) {
      reader.moveDown();
      values.add(parseValue(reader, frameAccessor));
      reader.moveUp();
    }

    return values;
  }

  private static PyDebugValue parseValue(final XppReader reader, PyFrameAccessor frameAccessor) throws PyDebuggerException {
    if (!"var".equals(reader.getNodeName())) {
      throw new PyDebuggerException("Expected <var>, found " + reader.getNodeName());
    }

    final String name = readString(reader, "name", null);
    final String type = readString(reader, "type", null);
    String value = readString(reader, "value", null);
    final String isContainer = readString(reader, "isContainer", "");
    final String isErrorOnEval = readString(reader, "isErrorOnEval", "");

    if (value.startsWith(type + ": ")) {  // drop unneeded prefix
      value = value.substring(type.length() + 2);
    }

    return new PyDebugValue(name, type, value, "True".equals(isContainer), "True".equals(isErrorOnEval), frameAccessor);
  }

  public static ArrayChunk parseArrayValues(final String text, final PyFrameAccessor frameAccessor) throws PyDebuggerException {
    final XppReader reader = openReader(text, false);
    ArrayChunk result = null;
    if (reader.hasMoreChildren()) {
      reader.moveDown();
      if (!"array".equals(reader.getNodeName())) {
        throw new PyDebuggerException("Expected <array> at first node, found " + reader.getNodeName());
      }
      String slice = readString(reader, "slice", null);
      int rows = readInt(reader, "rows", null);
      int cols = readInt(reader, "cols", null);
      String format = "%" + readString(reader, "format", null);
      String type = readString(reader, "type", null);
      String max = readString(reader, "max", null);
      String min = readString(reader, "min", null);
      result =
        new ArrayChunk(new PyDebugValue(slice, null, null, false, false, frameAccessor), slice, rows, cols, max, min, format, type, null);
      reader.moveUp();
    }

    Object[][] data = parseArrayValues(reader, frameAccessor);
    return new ArrayChunk(result.getValue(), result.getSlicePresentation(), result.getRows(), result.getColumns(), result.getMax(),
                          result.getMin(), result.getFormat(), result.getType(), data);
  }

  public static Object[][] parseArrayValues(final XppReader reader, final PyFrameAccessor frameAccessor) throws PyDebuggerException {
    int rows = -1;
    int cols = -1;
    if (reader.hasMoreChildren()) {
      reader.moveDown();
      if (!"arraydata".equals(reader.getNodeName())) {
        throw new PyDebuggerException("Expected <arraydata> at second node, found " + reader.getNodeName());
      }
      rows = readInt(reader, "rows", null);
      cols = readInt(reader, "cols", null);
      reader.moveUp();
    }

    if (rows <= 0 || cols <= 0) {
      throw new PyDebuggerException("Array xml: bad rows or columns number: (" + rows + ", " + cols + ")");
    }
    Object[][] values = new Object[rows][cols];

    int currRow = 0;
    int currCol = 0;
    while (reader.hasMoreChildren()) {
      reader.moveDown();
      if (!"var".equals(reader.getNodeName()) && !"row".equals(reader.getNodeName())) {
        throw new PyDebuggerException("Expected <var> or <row>, found " + reader.getNodeName());
      }
      if ("row".equals(reader.getNodeName())) {
        int index = readInt(reader, "index", null);
        if (currRow != index) {
          throw new PyDebuggerException("Array xml: expected " + currRow + " row, found " + index);
        }
        if (currRow > 0 && currCol != cols) {
          throw new PyDebuggerException("Array xml: expected " + cols + " filled columns, got " + currCol + " instead.");
        }
        currRow += 1;
        currCol = 0;
      }
      else {
        PyDebugValue value = parseValue(reader, frameAccessor);
        values[currRow - 1][currCol] = value.getValue();
        currCol += 1;
      }
      reader.moveUp();
    }

    return values;
  }

  private static XppReader openReader(final String text, final boolean checkForContent) throws PyDebuggerException {
    final XppReader reader = new XppReader(new StringReader(text), new MXParser(), new NoNameCoder());
    if (checkForContent && !reader.hasMoreChildren()) {
      throw new PyDebuggerException("Empty frame: " + text);
    }
    return reader;
  }

  private static String readString(final XppReader reader, final String name, final String fallback) throws PyDebuggerException {
    final String value;
    try {
      value = read(reader, name);
    }
    catch (PyDebuggerException e) {
      if (fallback != null) {
        return fallback;
      }
      else {
        throw e;
      }
    }
    return decode(value);
  }

  private static int readInt(final XppReader reader, final String name, final Integer fallback) throws PyDebuggerException {
    final String value;
    try {
      value = read(reader, name);
    }
    catch (PyDebuggerException e) {
      if (fallback != null) {
        return fallback;
      }
      else {
        throw e;
      }
    }
    try {
      return Integer.parseInt(value);
    }
    catch (NumberFormatException e) {
      throw new PyDebuggerException("Unable to decode " + value + ": " + e.getMessage());
    }
  }

  private static String read(final XppReader reader, final String name) throws PyDebuggerException {
    final String value = reader.getAttribute(name);
    if (value == null) {
      throw new PyDebuggerException("Attribute not found: " + name);
    }
    return value;
  }
}
