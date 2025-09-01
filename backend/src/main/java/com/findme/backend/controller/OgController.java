package com.findme.backend.controller;

import com.findme.backend.entity.ResultEntity;
import com.findme.backend.repository.ResultRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.time.ZoneId;
import java.util.Date;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/og")
@RequiredArgsConstructor
public class OgController {

    private final ResultRepository resultRepository;

    @GetMapping(value = "/{id}.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> getOgImage(@PathVariable Long id) {
        Optional<ResultEntity> resultOptional = resultRepository.findById(id);

        if (resultOptional.isEmpty()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        ResultEntity result = resultOptional.get();

        int width = 1200;
        int height = 630;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = image.createGraphics();

        // Background
        g2d.setColor(Color.WHITE); // Light background
        g2d.fillRect(0, 0, width, height);

        // Text rendering hints for quality
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // Title
        g2d.setColor(Color.BLACK);
        g2d.setFont(new Font("Arial", Font.BOLD, 60));
        String title = "find-me 결과 #" + result.getId();
        int titleWidth = g2d.getFontMetrics().stringWidth(title);
        g2d.drawString(title, (width - titleWidth) / 2, 200);

        // Subtitle (Score, Traits, Date)
        g2d.setFont(new Font("Arial", Font.PLAIN, 30));
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy.MM.dd");
        String dateStr = sdf.format(Date.from(result.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant()));
        String subtitle = String.format("점수 %.1f | %s | %s", result.getScore(), result.getTraits(), dateStr);
        int subtitleWidth = g2d.getFontMetrics().stringWidth(subtitle);
        g2d.drawString(subtitle, (width - subtitleWidth) / 2, 300);

        // Watermark
        g2d.setFont(new Font("Arial", Font.PLAIN, 20));
        g2d.setColor(Color.LIGHT_GRAY);
        String watermark = "find-me.app";
        int watermarkWidth = g2d.getFontMetrics().stringWidth(watermark);
        g2d.drawString(watermark, width - watermarkWidth - 20, height - 30);

        g2d.dispose();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", baos);
        } catch (IOException e) {
            e.printStackTrace();
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.IMAGE_PNG);
        // Optional: Cache-Control header for 1 hour
        headers.setCacheControl("public, max-age=3600");

        return new ResponseEntity<>(baos.toByteArray(), headers, HttpStatus.OK);
    }
}