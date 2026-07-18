package com.ahmadre.hinata.notification;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface ConnectHandshakeRepository extends MongoRepository<ConnectHandshake, String> {
}
