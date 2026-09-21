package com.ahmadre.hinata.timeoff;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Collection;
import java.util.List;

interface TimeOffNoticeRepository extends MongoRepository<TimeOffNotice, String> {

	Page<TimeOffNotice> findByUserIdOrderBySentAtDesc(String userId, Pageable pageable);

	List<TimeOffNotice> findByTypeIdAndYearAndUserIdIn(String typeId, Integer year, Collection<String> userIds);

	List<TimeOffNotice> findByUserIdOrderBySentAtDesc(String userId);
}
