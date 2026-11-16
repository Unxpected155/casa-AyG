package com.casaayg.category;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.casaayg.category.CategoryService.CategoryView;
import com.casaayg.domain.CategoryGroup;
import com.casaayg.domain.Text.CategoryName;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

@RestController
@RequestMapping("/api/spaces/{spaceId}/categories")
class CategoryController {

    private final CategoryService categories;

    CategoryController(CategoryService categories) {
        this.categories = categories;
    }

    /** DTO de entrada: solo lo que el usuario puede decidir. Sin space_id ni created_by. */
    record CategoryRequest(@NotNull String name, @NotNull CategoryGroup group, Boolean active) {}

    @GetMapping
    List<CategoryView> list(@PathVariable UUID spaceId) {
        return categories.list(spaceId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    CategoryView create(@PathVariable UUID spaceId, @Valid @RequestBody CategoryRequest body) {
        return categories.create(spaceId, new CategoryName(body.name()), body.group());
    }

    @PutMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void update(@PathVariable UUID spaceId, @PathVariable UUID id, @Valid @RequestBody CategoryRequest body) {
        categories.update(spaceId, id, new CategoryName(body.name()), body.group(),
            body.active() == null || body.active());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID spaceId, @PathVariable UUID id) {
        categories.delete(spaceId, id);
    }
}
