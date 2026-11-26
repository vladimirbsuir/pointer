package dev.pointer.transfer;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transfers")
@RequiredArgsConstructor
class TransferController {

    private final TransferService transfers;

    @PutMapping("/{ticket}")
    ResponseEntity<Void> upload(@PathVariable String ticket, HttpServletRequest request) throws IOException {
        transfers.receive(ticket, request.getInputStream());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{ticket}")
    ResponseEntity<Resource> download(@PathVariable String ticket) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(transfers.download(ticket)));
    }
}
