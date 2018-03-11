/*
 * Copyright (c) 2010-2018, b3log.org & hacpai.com
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
package org.b3log.solo.processor;

import com.qiniu.storage.UploadManager;
import com.qiniu.util.Auth;
import jodd.io.FileUtil;
import jodd.upload.FileUpload;
import jodd.upload.MultipartStreamParser;
import jodd.upload.impl.MemoryFileUploadFactory;
import jodd.util.MimeTypes;
import jodd.util.URLDecoder;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.time.DateFormatUtils;
import org.b3log.latke.Latkes;
import org.b3log.latke.ioc.LatkeBeanManager;
import org.b3log.latke.ioc.Lifecycle;
import org.b3log.latke.logging.Level;
import org.b3log.latke.logging.Logger;
import org.b3log.latke.util.MD5;
import org.b3log.solo.SoloServletListener;
import org.b3log.solo.model.Option;
import org.b3log.solo.service.OptionQueryService;
import org.b3log.solo.util.Solos;
import org.json.JSONObject;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.util.*;

/**
 * File upload to local.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding</a>
 * @version 1.0.0.0, Mar 11, 2018
 * @since 2.8.0
 */
@WebServlet(urlPatterns = {"/upload", "/upload/*"}, loadOnStartup = 2)
public class FileUploadServlet extends HttpServlet {

    /**
     * Serial version UID.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(FileUploadServlet.class);

    /**
     * Qiniu enabled.
     */
    private static final Boolean QN_ENABLED = StringUtils.isBlank(Solos.UPLOAD_DIR_PATH);

    static {
        if (!QN_ENABLED) {
            final File file = new File(Solos.UPLOAD_DIR_PATH);
            if (!FileUtil.isExistingFolder(file)) {
                try {
                    FileUtil.mkdirs(Solos.UPLOAD_DIR_PATH);
                } catch (IOException ex) {
                    LOGGER.log(Level.ERROR, "Init upload dir error", ex);

                    System.exit(-1);
                }
            }

            LOGGER.info("Uses dir [" + file.getAbsolutePath() + "] for saving files uploaded");
        }
    }

    @Override
    public void doGet(final HttpServletRequest req, final HttpServletResponse resp) throws ServletException, IOException {
        if (QN_ENABLED) {
            return;
        }

        final String uri = req.getRequestURI();
        String key = StringUtils.substringAfter(uri, "/upload/");
        key = StringUtils.substringBeforeLast(key, "?"); // Erase Qiniu template
        key = StringUtils.substringBeforeLast(key, "?"); // Erase Qiniu template

        String path = Solos.UPLOAD_DIR_PATH + key;
        path = URLDecoder.decode(path, "UTF-8");

        if (!FileUtil.isExistingFile(new File(path))) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);

            return;
        }

        final byte[] data = IOUtils.toByteArray(new FileInputStream(path));

        final String ifNoneMatch = req.getHeader("If-None-Match");
        final String etag = "\"" + MD5.hash(new String(data)) + "\"";

        resp.addHeader("Cache-Control", "public, max-age=31536000");
        resp.addHeader("ETag", etag);
        resp.setHeader("Server", "Latke Static Server (v" + SoloServletListener.VERSION + ")");
        final String ext = StringUtils.substringAfterLast(path, ".");
        final String mimeType = MimeTypes.getMimeType(ext);
        resp.addHeader("Content-Type", mimeType);

        if (etag.equals(ifNoneMatch)) {
            resp.setStatus(HttpServletResponse.SC_NOT_MODIFIED);

            return;
        }

        final OutputStream output = resp.getOutputStream();
        IOUtils.write(data, output);
        output.flush();

        IOUtils.closeQuietly(output);
    }

    @Override
    public void doPost(final HttpServletRequest req, final HttpServletResponse resp) throws ServletException, IOException {
        final int maxSize = 1024 * 1024 * 100;
        final MultipartStreamParser parser = new MultipartStreamParser(new MemoryFileUploadFactory().setMaxFileSize(maxSize));
        parser.parseRequestStream(req.getInputStream(), "UTF-8");
        final List<String> errFiles = new ArrayList();
        final Map<String, String> succMap = new HashMap<>();
        final FileUpload[] files = parser.getFiles("file[]");
        final String[] names = parser.getParameterValues("name[]");
        String fileName = "";

        Auth auth;
        UploadManager uploadManager = null;
        String uploadToken = null;
        JSONObject qiniu = null;
        final String date = DateFormatUtils.format(System.currentTimeMillis(), "yyyy/MM");
        if (QN_ENABLED) {
            try {
                final LatkeBeanManager beanManager = Lifecycle.getBeanManager();
                final OptionQueryService optionQueryService = beanManager.getReference(OptionQueryService.class);
                qiniu = optionQueryService.getOptions(Option.CATEGORY_C_QINIU);
                if (null == qiniu) {
                    LOGGER.log(Level.ERROR, "Qiniu settings failed, please visit https://hacpai.com/article/1442418791213 for more details");

                    return;
                }

                auth = Auth.create(qiniu.optString(Option.ID_C_QINIU_ACCESS_KEY), qiniu.optString(Option.ID_C_QINIU_SECRET_KEY));
                uploadToken = auth.uploadToken(qiniu.optString(Option.ID_C_QINIU_BUCKET), null, 3600 * 6, null);
            } catch (final Exception e) {
                LOGGER.log(Level.ERROR, "Qiniu settings failed, please visit https://hacpai.com/article/1442418791213 for more details", e);

                return;
            }
        }

        for (int i = 0; i < files.length; i++) {
            final FileUpload file = files[i];
            final String originalName = fileName = file.getHeader().getFileName();
            try {
                String suffix = StringUtils.substringAfterLast(fileName, ".");
                final String contentType = file.getHeader().getContentType();
                if (StringUtils.isBlank(suffix)) {
                    String[] exts = MimeTypes.findExtensionsByMimeTypes(contentType, false);
                    if (null != exts && 0 < exts.length) {
                        suffix = exts[0];
                    } else {
                        suffix = StringUtils.substringAfter(contentType, "/");
                    }
                }

                final String name = StringUtils.substringBeforeLast(fileName, ".");
                final String processName = name.replaceAll("\\W", "");
                final String uuid = UUID.randomUUID().toString().replaceAll("-", "");
                fileName = uuid + '_' + processName + "." + suffix;

                if (QN_ENABLED) {
                    fileName = "file/" + date + "/" + fileName;
                    if (!ArrayUtils.isEmpty(names)) {
                        fileName = names[i];
                    }
                    uploadManager.put(file.getFileInputStream(), fileName, uploadToken, null, contentType);
                    succMap.put(originalName, qiniu.optString(Option.ID_C_QINIU_DOMAIN) + "/" + fileName);
                } else {
                    final OutputStream output = new FileOutputStream(Solos.UPLOAD_DIR_PATH + fileName);
                    IOUtils.copy(file.getFileInputStream(), output);
                    IOUtils.closeQuietly(file.getFileInputStream());
                    IOUtils.closeQuietly(output);
                    succMap.put(originalName, Latkes.getServePath() + "/upload/" + fileName);
                }
            } catch (final Exception e) {
                LOGGER.log(Level.ERROR, "Uploads file failed", e);

                errFiles.add(originalName);
            }
        }

        final JSONObject result = new JSONObject();
        final JSONObject data = new JSONObject();
        data.put("errFiles", errFiles);
        data.put("succMap", succMap);
        result.put("data", data);
        result.put("code", 0);
        result.put("msg", "");
        resp.setContentType("application/json");
        final PrintWriter writer = resp.getWriter();
        writer.append(result.toString());
        writer.flush();
        writer.close();
    }
}
