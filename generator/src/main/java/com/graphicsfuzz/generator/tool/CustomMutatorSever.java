/*
 * Copyright 2018 The GraphicsFuzz Project Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.graphicsfuzz.generator.tool;

import com.graphicsfuzz.common.ast.TranslationUnit;
import com.graphicsfuzz.common.tool.PrettyPrinterVisitor;
import com.graphicsfuzz.common.util.GlslParserException;
import com.graphicsfuzz.common.util.ParseHelper;
import com.graphicsfuzz.common.util.ParseTimeoutException;
import com.graphicsfuzz.common.util.RandomWrapper;
import com.graphicsfuzz.common.util.ShaderKind;
import com.graphicsfuzz.generator.fuzzer.FuzzedIntoACornerException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

public class CustomMutatorSever {
  public static void main(String[] args) {
    try {
      runServer(8666);
    } catch (IOException | ParseTimeoutException | InterruptedException exception) {
      exception.printStackTrace();
      System.exit(1);
    }
  }

  private static void runServer(int port)
      throws IOException, ParseTimeoutException, InterruptedException {
    ByteArrayOutputStream byteArrayOutputStream;
    TranslationUnit tu;
    byte[] headerBuff = new byte[28];
    ServerSocket serverSocket = new ServerSocket(port);
    Socket socket = serverSocket.accept();
    InputStream inputStream = socket.getInputStream();
    OutputStream outputStream = socket.getOutputStream();
    while (true) {
      // TODO(metzman) Figure out a better way to handle waiting for the header to arrive.
      while (inputStream.available() < headerBuff.length) ;
      inputStream.read(headerBuff, 0, headerBuff.length);
      ByteBuffer bb = ByteBuffer.wrap(headerBuff);
      bb.order(ByteOrder.LITTLE_ENDIAN);
      long size1 = bb.getLong();
      long size2 = bb.getLong();
      // GraphicsFuzz can't do anything with this unfortunately.
      long maxOutSize = bb.getLong();
      int libfuzzerSeed = bb.getInt();
      byte[] inputDataBuff = new byte[((int) size1) + ((int) size2)];
      socket.getInputStream().read(inputDataBuff, 0, inputDataBuff.length);
      try {
        String s = new String(inputDataBuff);
        tu = ParseHelper.parse(s, ShaderKind.FRAGMENT);
        Mutate.mutate(tu, new RandomWrapper(libfuzzerSeed));
        byteArrayOutputStream = new ByteArrayOutputStream();
        try (PrintStream stream = new PrintStream(byteArrayOutputStream, true, "UTF-8")) {
          PrettyPrinterVisitor.emitShader(
              tu,
              Optional.empty(),
              stream,
              PrettyPrinterVisitor.DEFAULT_INDENTATION_WIDTH,
              PrettyPrinterVisitor.DEFAULT_NEWLINE_SUPPLIER,
              false);
          String data = new String(byteArrayOutputStream.toByteArray(), StandardCharsets.UTF_8);
          ByteBuffer lengthByteBuffer = ByteBuffer.allocate(8);
          lengthByteBuffer.order(ByteOrder.LITTLE_ENDIAN);
          lengthByteBuffer.putLong((long) data.length());
          byte[] length = new byte[8];
          lengthByteBuffer.position(0);
          lengthByteBuffer.get(length);
          outputStream.write(length);
          outputStream.write(data.getBytes());
        }
      } catch (GlslParserException | FuzzedIntoACornerException exception) {
        exception.printStackTrace();
        System.out.println(new String(inputDataBuff));
        for (int idx = 0; idx < 8; idx++) {
          outputStream.write(0);
        }
      }
    }
  }
}
