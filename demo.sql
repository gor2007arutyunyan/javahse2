-- Демонстрационный сценарий. Запуск в консоли СУБД:  .read demo.sql
-- Две таблицы, связанные по ключу: rentals.car_id -> cars.id

CREATE TABLE cars (id INT PRIMARY KEY, brand STRING, price DOUBLE, available BOOLEAN);
CREATE TABLE rentals (
    id INT PRIMARY KEY,
    car_id INT NOT NULL REFERENCES cars(id),
    customer STRING NOT NULL,
    rent_date DATE,
    days INT
);

-- Примеры из задания
INSERT INTO cars VALUES (1, 'Toyota', 25000.0, TRUE);
INSERT INTO cars (id, brand) VALUES (2, 'BMW');
SELECT * FROM cars;
SELECT brand, price FROM cars WHERE price > 20000;
UPDATE cars SET price = 30000 WHERE id = 2;

-- Вставка нескольких записей одной командой
INSERT INTO cars VALUES (3, 'Audi', 41000.5, TRUE), (4, 'Toyota', 18000, FALSE), (5, 'Skoda', 15500.0, TRUE);
INSERT INTO rentals VALUES (1, 1, 'Иванов', '2026-11-01', 3), (2, 3, 'Петров', '2026-11-05', 7), (3, 1, 'Сидорова', '2026-11-09', 2);

SELECT * FROM cars ORDER BY price DESC;
SELECT customer, rent_date FROM rentals WHERE days >= 3 AND rent_date < '2026-11-08';
SELECT COUNT(*) FROM cars WHERE brand = 'Toyota' OR available = FALSE;

-- Индекс и его использование
CREATE INDEX idx_cars_brand ON cars (brand);
SELECT * FROM cars WHERE brand = 'Toyota';
SHOW INDEXES;

-- Удаление записей
DELETE FROM cars WHERE brand = 'Skoda';
SELECT * FROM cars;
SHOW TABLES;
DESCRIBE rentals;
