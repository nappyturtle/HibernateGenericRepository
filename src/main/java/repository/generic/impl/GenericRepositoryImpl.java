package repository.generic.impl;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.hibernate.query.Query;
import org.hibernate.type.StringNVarcharType;
import repository.generic.GenericEntity;
import repository.generic.GenericRepository;

import javax.persistence.Table;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @version 1.1
 * @author NAMBACH
 * @param <T> entity of repository that implements {@link GenericRepository}
 */

public abstract class GenericRepositoryImpl<T extends GenericEntity> implements GenericRepository<T> {

    private static final int BATCH_SIZE = 10;

    public static SessionFactory getFactory() {
        return new Configuration().configure().buildSessionFactory();
    }

    protected String tableName;
    private Class<T> clazz;

    private void getTableName() {
        Class<T> clazz = ((Class)((ParameterizedType)this.getClass().getGenericSuperclass())
                .getActualTypeArguments()[0]);
        this.clazz = clazz;

        Table tableName = clazz.getAnnotation(Table.class);
        this.tableName = tableName.name().replaceAll("`", "");
    }

    protected SessionFactory sessionFactory;

    protected GenericRepositoryImpl(SessionFactory sessionFactory) {
        this.sessionFactory = sessionFactory;
        getTableName();
    }

    public void insertOrReplace(T toReplace) {
        try (Session session = sessionFactory.openSession()) {
            session.beginTransaction();

            session.saveOrUpdate(toReplace);

            session.getTransaction().commit();
            session.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void insertOrReplaceBatch(List<T> entities) {
        try (Session session = sessionFactory.openSession()) {
            session.beginTransaction();

            for (int i = 0; i < entities.size(); i++) {
                session.saveOrUpdate(entities.get(i));
                if (i % BATCH_SIZE == 0) { // Same as the JDBC batch size
                    //flush a batch of inserts and release memory:
                    session.flush();
                    session.clear();
                }
            }

            session.getTransaction().commit();
            session.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void updateProperties(T toReplace, String... properties) {
        try (Session session = sessionFactory.openSession()) {
            session.beginTransaction();

            T entity = session.get(clazz, toReplace.getEntityId());

            if (entity == null) {
                return;
            }

            for (String property : properties) {
                try {
                    Field field = clazz.getDeclaredField(property);
                    field.setAccessible(true);
                    field.set(entity, field.get(toReplace));
                } catch (NoSuchFieldException | IllegalAccessException e) {
                    e.printStackTrace();
                }
            }

            session.save(entity);
            session.getTransaction().commit();
            session.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public List<T> searchAll() {
        List<T> list = new ArrayList<>();
        try (Session session = sessionFactory.openSession()) {
            session.beginTransaction();
            list = session.createQuery("from " + tableName, clazz).list();

            session.close();
            return list;
        } catch (Exception e) {
            e.printStackTrace();
            return list;
        }
    }

    public List<T> searchAlikeColumn(Map<String, String> keyValues) {
        List<T> list = new ArrayList<>();
        try (Session session = sessionFactory.openSession()) {
            session.beginTransaction();

            //e.g: key1 LIKE :key1 OR key2 LIKE :key2
            String queryParams = keyValues.keySet()
                    .stream()
                    .map(s -> s + " like :" + s)
                    .collect(Collectors.joining(" or "));

            Query<T> query = session.createQuery("from " + tableName + " where " + queryParams, clazz);

            keyValues.forEach((key, value) -> query.setParameter(key, "%" + value + "%", StringNVarcharType.INSTANCE));

            list = query.list();

            session.close();
            return list;
        } catch (Exception e) {
            e.printStackTrace();
            return list;
        }
    }

    public List<T> searchExactColumn(List<String> values, String columnName) {
        List<T> list = new ArrayList<>();
        try (Session session = sessionFactory.openSession()) {
            session.beginTransaction();

            //e.g: id IN (:param0, :param1, :param2)
            String queryParams = columnName + " in " + values.stream().map(s -> ":param" + values.indexOf(s)).collect(Collectors.joining(", ", "(", ")"));

            Query<T> query = session.createQuery("from " + tableName + " where " + queryParams, clazz);

            for (int i = 0; i < values.size(); i++) {
                query.setParameter("param" + i, values.get(i), StringNVarcharType.INSTANCE);
            }

            list = query.list();

            session.close();
            return list;
        } catch (Exception e) {
            e.printStackTrace();
            return list;
        }
    }

    @Override
    public List<T> searchExactColumn(Map<String, List<String>> keyValues, String logicalOperator) {
        List<T> list = new ArrayList<>();
        try (Session session = sessionFactory.openSession()) {
            session.beginTransaction();

            // e.g
            // id IN (:id0, :id1, :id2)
            // AND/OR
            // name IN (:name0, :name1, :name2)
            String queryParam = getExactQueryParam(keyValues, logicalOperator);

            Query<T> query = session.createQuery("from " + tableName + " where " + queryParam, clazz);

            keyValues.forEach((column, values) -> {
                for (int i = 0; i < values.size(); i++) {
                    query.setParameter(String.format("%s%d", column, i), values.get(i), StringNVarcharType.INSTANCE);
                }
            });

            list = query.list();

            session.close();
            return list;
        } catch (Exception e) {
            e.printStackTrace();
            return list;
        }
    }

    private String getExactQueryParam(Map<String, List<String>> keyValue, String logicalOperator) {
        List<String> columns = new ArrayList<>();

        keyValue.forEach((column, values) -> {
            // (:[columnName][index], ...)
            //e.g
            // (:id0, id1, id2)
            String valueSet = values.stream()
                    .map(value -> String.format(":%s%d", column, values.indexOf(value)))
                    .collect(Collectors.joining(", ", "(", ")"));

            columns.add(column + " in " + valueSet);
        });

        return columns.stream()
                .collect(Collectors.joining(" " + logicalOperator + " ", " ", " "));
    }

    public T findById(T entity) {
        Session session = null;
        try {
            session = sessionFactory.openSession();

            return session.get(clazz, entity.getEntityId());
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        } finally {
            if (session != null) {
                session.close();
            }
        }
    }

    @Override
    public T findById(String id) {
        try (Session session = sessionFactory.openSession()) {

            return session.get(clazz, id);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public T delete(T toDelete) {
        try (Session session = sessionFactory.openSession()) {
            session.beginTransaction();

            T entity = session.load(clazz, toDelete.getEntityId());
            if (entity != null) {
                session.delete(entity);
                session.getTransaction().commit();
            }

            session.close();
            return entity;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public void truncateTable() {
        try (Session session = sessionFactory.openSession()) {
            session.beginTransaction();

            Query query = session.createQuery("delete from " + tableName);
            query.executeUpdate();

            session.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
