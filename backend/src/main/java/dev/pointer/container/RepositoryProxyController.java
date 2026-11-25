package dev.pointer.container;

import dev.pointer.common.security.AuthPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/runner/git/{owner}/{name}.git")
@RequiredArgsConstructor
class RepositoryProxyController {

    private final RepositoryProxy proxy;

    @GetMapping("/info/refs")
    void advertise(
            AuthPrincipal principal,
            @PathVariable String owner,
            @PathVariable String name,
            @RequestParam String service,
            HttpServletRequest request,
            HttpServletResponse response)
            throws IOException {
        proxy.advertise(principal.deviceId(), owner, name, service, request, response);
    }

    @PostMapping("/" + RepositoryProxy.UPLOAD_PACK)
    void uploadPack(
            AuthPrincipal principal,
            @PathVariable String owner,
            @PathVariable String name,
            HttpServletRequest request,
            HttpServletResponse response)
            throws IOException {
        proxy.uploadPack(principal.deviceId(), owner, name, request, response);
    }

    @PostMapping("/" + RepositoryProxy.RECEIVE_PACK)
    void receivePack(
            AuthPrincipal principal,
            @PathVariable String owner,
            @PathVariable String name,
            HttpServletRequest request,
            HttpServletResponse response)
            throws IOException {
        proxy.receivePack(principal.deviceId(), owner, name, request, response);
    }
}
