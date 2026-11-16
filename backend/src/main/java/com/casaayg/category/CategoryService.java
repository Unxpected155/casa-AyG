package com.casaayg.category;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.casaayg.domain.CategoryGroup;
import com.casaayg.domain.Text.CategoryName;
import com.casaayg.shared.security.CurrentUser;
import com.casaayg.shared.web.ConflictException;
import com.casaayg.shared.web.NotFoundException;
import com.casaayg.space.Spaces;

/**
 * Cada consulta filtra por (id, space_id), nunca solo por id: así un id de otro espacio
 * no encuentra nada (404), aunque RLS no existiera.
 */
@Service
public class CategoryService {

    private final JdbcClient jdbc;
    private final Spaces spaces;

    CategoryService(JdbcClient jdbc, Spaces spaces) {
        this.jdbc = jdbc;
        this.spaces = spaces;
    }

    public record CategoryView(UUID id, String name, CategoryGroup group, boolean active) {}

    @Transactional(readOnly = true)
    public List<CategoryView> list(UUID spaceId) {
        spaces.requireMember(spaceId);
        return jdbc.sql("""
                SELECT id, name, category_group, active FROM category
                 WHERE space_id = ? ORDER BY category_group, lower(name)""")
            .param(spaceId)
            .query((rs, n) -> new CategoryView(rs.getObject("id", UUID.class), rs.getString("name"),
                CategoryGroup.valueOf(rs.getString("category_group")), rs.getBoolean("active")))
            .list();
    }

    @Transactional
    public CategoryView create(UUID spaceId, CategoryName name, CategoryGroup group) {
        spaces.requireWritable(spaceId);
        UUID id = jdbc.sql("""
                INSERT INTO category (space_id, created_by, name, category_group)
                VALUES (?, ?, ?, ?) RETURNING id""")
            .params(spaceId, CurrentUser.require(), name.value(), group.name())
            .query(UUID.class).single();
        return new CategoryView(id, name.value(), group, true);
    }

    @Transactional
    public void update(UUID spaceId, UUID id, CategoryName name, CategoryGroup group, boolean active) {
        spaces.requireWritable(spaceId);
        int rows = jdbc.sql("UPDATE category SET name = ?, category_group = ?, active = ? WHERE id = ? AND space_id = ?")
            .params(name.value(), group.name(), active, id, spaceId)
            .update();
        if (rows == 0) {
            throw new NotFoundException();
        }
    }

    @Transactional
    public void delete(UUID spaceId, UUID id) {
        spaces.requireWritable(spaceId);
        boolean used = jdbc.sql("SELECT EXISTS (SELECT 1 FROM transaction WHERE category_id = ? AND space_id = ?)")
            .params(id, spaceId).query(Boolean.class).single();
        if (used) {
            throw new ConflictException("La categoría tiene movimientos: desactívala en lugar de borrarla.");
        }
        int rows = jdbc.sql("DELETE FROM category WHERE id = ? AND space_id = ?").params(id, spaceId).update();
        if (rows == 0) {
            throw new NotFoundException();
        }
    }
}
