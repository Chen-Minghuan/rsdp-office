package com.rsdp.officeproduct.importer.service;

import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.config.RenderProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class RenderPreviewFiles {
    private final RenderProperties properties;
    private final HttpClient downloads=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();

    public String reference(String id, byte[] bytes) { return save(id + "-reference.png",bytes); }

    public String download(String id, String url) {
        try {
            URI uri=URI.create(url);
            // Only provider-controlled OSS result URLs; never send API credentials to the image host.
            if (!"https".equals(uri.getScheme()) || uri.getHost()==null || !uri.getHost().toLowerCase(Locale.ROOT).endsWith(".aliyuncs.com") || uri.getUserInfo()!=null || (uri.getPort()!=-1 && uri.getPort()!=443)) throw new BusinessException("生图结果地址无效");
            var response=downloads.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(45)).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            try (InputStream in=response.body()) {
                if (response.statusCode()!=200) throw new BusinessException("效果图暂未下载成功，请再次查询任务，无需重新生成");
                bytes=in.readNBytes(20_000_001);
            }
            if (bytes.length>20_000_000) throw new BusinessException("效果图文件超过保存限制");
            try (var stream=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers=ImageIO.getImageReaders(stream);
                if (!readers.hasNext()) throw new BusinessException("生图服务未返回有效图片");
                var reader=readers.next();
                try { reader.setInput(stream); if ((long)reader.getWidth(0)*reader.getHeight(0)>20_000_000) throw new BusinessException("效果图分辨率超过保存限制"); }
                finally { reader.dispose(); }
            }
            BufferedImage image=ImageIO.read(new ByteArrayInputStream(bytes));
            if (image==null) throw new BusinessException("生图服务未返回有效图片");
            var output=new java.io.ByteArrayOutputStream(); ImageIO.write(image,"png",output);
            return save(id+".png",output.toByteArray());
        } catch (BusinessException e) { throw e; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new BusinessException("效果图下载中断，请再次查询任务"); }
        catch (Exception e) { throw new BusinessException("效果图下载或保存失败，请再次查询任务，无需重新生成"); }
    }

    private String save(String filename, byte[] bytes) {
        try {
            Path directory=Path.of(properties.getStorageDirectory()).toAbsolutePath(); Files.createDirectories(directory);
            Path temporary=Files.createTempFile(directory,"render-",".tmp");
            try { Files.write(temporary,bytes); Files.move(temporary,directory.resolve(filename),StandardCopyOption.REPLACE_EXISTING); }
            finally { Files.deleteIfExists(temporary); }
            return "/uploads/render-previews/"+filename;
        } catch (Exception e) { throw new BusinessException("效果图保存失败，请检查生图目录写入权限"); }
    }
}
