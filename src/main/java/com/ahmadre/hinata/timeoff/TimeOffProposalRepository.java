package com.ahmadre.hinata.timeoff;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

interface TimeOffProposalRepository extends MongoRepository<TimeOffProposal, String> {

	Page<TimeOffProposal> findByStatusOrderByCreatedAtAsc(TimeOffProposal.Status status, Pageable pageable);

	List<TimeOffProposal> findByUserId(String userId);

	long deleteByUserIdAndStatus(String userId, TimeOffProposal.Status status);

	long countByStatus(TimeOffProposal.Status status);
}
