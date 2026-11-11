CREATE TABLE test_child (
    id INT PRIMARY KEY,
    parent_id INT NOT NULL REFERENCES test_parent(id)
);
