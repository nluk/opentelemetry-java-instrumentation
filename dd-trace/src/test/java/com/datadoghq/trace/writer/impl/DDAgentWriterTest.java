package com.datadoghq.trace.writer.impl;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.datadoghq.trace.DDBaseSpan;
import com.datadoghq.trace.DDSpan;
import com.datadoghq.trace.DDTracer;
import com.datadoghq.trace.writer.DDAgentWriter;
import com.datadoghq.trace.writer.DDApi;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public class DDAgentWriterTest {

  DDSpan parent = null;
  DDApi mockedAPI = null;
  List<List<DDBaseSpan<?>>> traces = new ArrayList<>();
  DDAgentWriter ddAgentWriter = null;

  @Before
  public void setUp() throws Exception {
    //Setup
    final DDTracer tracer = new DDTracer();

    parent = tracer.buildSpan("hello-world").withServiceName("service-name").startManual();
    parent.setBaggageItem("a-baggage", "value");

    Thread.sleep(100);

    final DDSpan child = tracer.buildSpan("hello-world").asChildOf(parent).startManual();
    Thread.sleep(100);

    child.finish();
    Thread.sleep(100);
    parent.finish();

    //Create DDWriter
    traces.add(parent.context().getTrace());
    mockedAPI = mock(DDApi.class);
    when(mockedAPI.sendTraces(traces)).thenReturn(true);
    ddAgentWriter = new DDAgentWriter(mockedAPI);
    ddAgentWriter.start();
  }

  @Test
  public void testWrite() throws Exception {
    ddAgentWriter.write(parent.context().getTrace());
    Thread.sleep(500);
    verify(mockedAPI).sendTraces(traces);
  }

  @Test
  public void testClose() throws Exception {
    ddAgentWriter.close();

    ddAgentWriter.write(parent.context().getTrace());
    Thread.sleep(500);
    verifyNoMoreInteractions(mockedAPI);
  }
}
