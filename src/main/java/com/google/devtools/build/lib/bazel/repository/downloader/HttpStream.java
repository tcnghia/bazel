// Copyright 2016 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.bazel.repository.downloader;

import com.google.common.base.Optional;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadCompatible;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.zip.GZIPInputStream;
import javax.annotation.WillCloseWhenClosed;

/**
 * Input stream that validates checksum resumes downloads on error.
 *
 * <p>This class is not thread safe, but it is safe to message pass its objects between threads.
 */
@ThreadCompatible
final class HttpStream extends FilterInputStream {

  private static final int GZIP_BUFFER_BYTES = 8192;  // same as ByteStreams#copy
  private static final ImmutableSet<String> GZIPPED_EXTENSIONS = ImmutableSet.of("gz", "tgz");
  private static final ImmutableSet<String> GZIP_CONTENT_ENCODING =
      ImmutableSet.of("gzip", "x-gzip");

  /** Factory for {@link HttpStream}. */
  @ThreadSafe
  static class Factory {

    private final ProgressInputStream.Factory progressInputStreamFactory;

    Factory(ProgressInputStream.Factory progressInputStreamFactory) {
      this.progressInputStreamFactory = progressInputStreamFactory;
    }

    HttpStream create(
        @WillCloseWhenClosed URLConnection connection,
        URL originalUrl,
        Optional<Checksum> checksum)
        throws IOException {
      return create(connection, originalUrl, checksum, Optional.<String>absent());
    }

    @SuppressWarnings("resource")
    HttpStream create(
        @WillCloseWhenClosed URLConnection connection,
        URL originalUrl,
        Optional<Checksum> checksum,
        Optional<String> type)
        throws IOException {
      InputStream stream = new InterruptibleInputStream(connection.getInputStream());
      try {
        stream = progressInputStreamFactory.create(stream, connection.getURL(), originalUrl);

        // Determine if we need to transparently gunzip. See RFC2616 § 3.5 and § 14.11. Please note
        // that some web servers will send Content-Encoding: gzip even when we didn't request it if
        // the file is a .gz file. Therefore we take the type parameter from the rule http_archive
        // in consideration. If the repository/file that we are downloading is already compressed we
        // should not decompress it to preserve the desired file format.
        if (GZIP_CONTENT_ENCODING.contains(Strings.nullToEmpty(connection.getContentEncoding()))
            && !GZIPPED_EXTENSIONS.contains(HttpUtils.getExtension(connection.getURL().getPath()))
            && !GZIPPED_EXTENSIONS.contains(HttpUtils.getExtension(originalUrl.getPath()))
            && !typeIsGZIP(type)) {
          stream = new GZIPInputStream(stream, GZIP_BUFFER_BYTES);
        }

        if (checksum.isPresent()) {
          stream = new HashInputStream(stream, checksum.get());
        }
      } catch (Exception e) {
        try {
          stream.close();
        } catch (IOException e2) {
          e.addSuppressed(e2);
        }
        throw e;
      }
      return new HttpStream(stream, connection.getURL());
    }

    /**
     * Checks if the given type is GZIP
     *
     * @param type extension, e.g. "tar.gz"
     * @return whether the type is GZIP or not
     */
    private static boolean typeIsGZIP(Optional<String> type) {
      if (type.isPresent()) {
        String t = type.get();

        if (t.contains(".")) {
          // We only want to look at the last extension.
          t = HttpUtils.getExtension(t);
        }

        return GZIPPED_EXTENSIONS.contains(t);
      }
      return false;
    }
  }

  private final URL url;

  HttpStream(@WillCloseWhenClosed InputStream delegate, URL url) {
    super(delegate);
    this.url = url;
  }

  /** Returns final redirected URL. */
  URL getUrl() {
    return url;
  }
}
