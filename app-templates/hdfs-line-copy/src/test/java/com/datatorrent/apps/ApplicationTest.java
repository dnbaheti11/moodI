/*
 * Copyright (c) 2012-2017 DataTorrent, Inc.
 * All Rights Reserved.
 * The use of this source code is governed by the Limited License located at
 * https://www.datatorrent.com/datatorrent-openview-software-license/
 */

package com.datatorrent.apps;

import java.io.File;
import java.io.IOException;

import javax.validation.ConstraintViolationException;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

import org.apache.commons.io.FileUtils;
import org.apache.hadoop.conf.Configuration;

import com.datatorrent.api.LocalMode;
import com.datatorrent.apps.Application;

/**
 * Test application in local mode.
 */
@org.apache.hadoop.classification.InterfaceStability.Evolving
public class ApplicationTest
{
  private String outputDir;

  public static class TestMeta extends TestWatcher
  {
    public String baseDirectory;

    @Override
    protected void starting(org.junit.runner.Description description)
    {
      this.baseDirectory = "target/" + description.getClassName() + "/" + description.getMethodName();
    }

    @Override
    protected void finished(Description description)
    {
      super.finished(description);
      try {
        FileUtils.forceDelete(new File(baseDirectory));
      } catch (IOException e) {
        e.printStackTrace();
      }
    }

  }

  @Rule
  public TestMeta testMeta = new TestMeta();

  @Before
  public void setup() throws Exception
  {
    outputDir = testMeta.baseDirectory + File.separator + "output";
  }

  @Test
  public void testApplication() throws IOException, Exception
  {
    try {
      LocalMode lma = LocalMode.newInstance();
      Configuration conf = new Configuration(false);
      conf.addResource(this.getClass().getResourceAsStream("/META-INF/properties-test.xml"));
      conf.set("dt.operator.fileOutput.prop.filePath", outputDir);
      File outputfile = FileUtils.getFile(outputDir, "output.txt_5.0");

      lma.prepareDAG(new Application(), conf);
      LocalMode.Controller lc = lma.getController();
      lc.runAsync();

      // wait for tuples to show up
      while (!outputfile.exists()) {
        System.out.println("Waiting for tuples ....");
        Thread.sleep(1000);
      }

      lc.shutdown();
      Assert.assertTrue(
          FileUtils.contentEquals(FileUtils.getFile("src/test/resources/test_event_data.txt"), outputfile));

    } catch (ConstraintViolationException e) {
      Assert.fail("constraint violations: " + e.getConstraintViolations());
    }
  }

}
