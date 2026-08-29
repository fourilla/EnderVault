package io.github.fourilla.endervault.web.trash;

import io.github.fourilla.endervault.web.support.ViteAssetService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminTrashController {

    private final ViteAssetService viteAssetService;

    public AdminTrashController(ViteAssetService viteAssetService) {
        this.viteAssetService = viteAssetService;
    }

    @GetMapping("/admin/trash")
    public String trash(Model model) {
        model.addAttribute("trashFrontend", viteAssetService.entry("src/trash/main.tsx"));
        return "trash";
    }

}
