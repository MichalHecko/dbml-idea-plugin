package dbml;

import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.*;

public class DbmlModelTest {
    static final String SAMPLE = """
            Project shop { database_type: 'PostgreSQL' }

            // dbdiagram.io sample
            Table users as U {
              id int [pk, increment]
              full_name varchar
              email varchar [unique, note: 'login']
              country_code int [ref: > countries.code]
              created_at timestamp [default: `now()`]
              Note: 'Registered users'
            }

            Table countries {
              code int [pk]
              name varchar
              continent_name "character varying"
            }

            Table public.orders [headercolor: #2D9C5B] {
              id int [pk]
              user_id int [not null]
              status order_status
              created_at varchar
              indexes { (id, user_id) [unique] }
            }

            Table order_items {
              order_id int
              product_id int
              quantity int [default: 1]
            }

            Table products {
              id int [pk]
              name varchar
              merchant_id int [not null]
              price decimal(10, 2)
              category_id int
            }

            Table merchants {
              id int
              country_code int
              merchant_name varchar
              admin_id int
              Note {
                '''
                Sellers
                '''
              }
            }

            Table categories {
              id int [pk]
              name varchar
              parent_id int [ref: > categories.id]
            }

            Table audit_log { id bigint [pk]
              payload jsonb }

            Enum order_status { created running done }

            Ref: orders.user_id > U.id
            Ref fk_items { order_items.order_id > orders.id [delete: cascade]
              order_items.product_id > products.id }
            Ref: products.merchant_id > merchants.id
            Ref: merchants.admin_id > users.id
            Ref: merchants.country_code > countries.code
            Ref: products.category_id > categories.id
            Ref: merchants.(id, country_code) <> products.(merchant_id, category_id)
            Ref: nope.id > users.id

            TableGroup catalog [color: #8E44AD] { products categories merchants }
            """;

    @Test
    public void parsesTablesRefsAndGroups() {
        DbmlModel m = DbmlModel.parse(SAMPLE);
        assertNull(m.error);
        assertEquals(List.of("users", "countries", "orders", "order_items", "products", "merchants", "categories", "audit_log"),
                List.copyOf(m.tables.keySet()));
        DbmlModel.Table users = m.tables.get("users");
        assertEquals("U", users.alias);
        assertEquals("Registered users", users.note);
        assertTrue(users.columns.getFirst().pk());
        assertEquals("login", users.columns.get(2).note());
        assertEquals("character varying", m.tables.get("countries").columns.get(2).type());
        assertEquals("decimal(10, 2)", m.tables.get("products").columns.get(3).type());
        assertEquals(0x2D9C5B, m.tables.get("orders").color.getRGB() & 0xFFFFFF);
        assertEquals("Sellers", m.tables.get("merchants").note);
        assertEquals(2, m.tables.get("audit_log").columns.size());

        // 2 inline + 8 resolved statements; "nope" is unknown and dropped, alias U resolves to users
        assertEquals(10, m.refs.size());
        assertTrue(m.refs.contains(new DbmlModel.Ref("orders", List.of("user_id"), ">", "users", List.of("id"))));
        assertTrue(m.refs.contains(new DbmlModel.Ref("merchants", List.of("id", "country_code"), "<>", "products", List.of("merchant_id", "category_id"))));
        assertEquals(List.of("products", "categories", "merchants"), m.groups.getFirst().tables());
    }

    @Test
    public void unbalancedTextIsAnError() {
        assertNotNull(DbmlModel.parse("Table a {\n id int\n").error);
        assertNotNull(DbmlModel.parse("Table a { id int [pk }").error);
        assertNull(DbmlModel.parse("").error);
    }

    @Test
    public void layoutPutsForeignKeysLeftAndDoesNotOverlap() throws Exception {
        DbmlModel m = DbmlModel.parse(SAMPLE);
        Map<String, Rectangle> box = DbmlDiagram.layout(m);
        assertTrue(box.get("order_items").x < box.get("orders").x);
        assertTrue(box.get("orders").x < box.get("users").x);
        for (Rectangle a : box.values()) for (Rectangle b : box.values()) assertTrue(a == b || !a.intersects(b));

        // rendered samples for eyeballing: build/sample.png, build/sample.svg
        ImageIO.write(DbmlDiagram.toImage(m, box, true, 2), "png", new File("build/sample.png"));
        String svg = DbmlDiagram.toSvg(m, box, true);
        assertTrue(svg.contains("<svg"));
        Files.writeString(new File("build/sample.svg").toPath(), svg, UTF_8);
    }
}
