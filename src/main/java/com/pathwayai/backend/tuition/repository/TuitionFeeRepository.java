package com.pathwayai.backend.tuition.repository;

import com.pathwayai.backend.tuition.entity.TuitionFee;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TuitionFeeRepository extends JpaRepository<TuitionFee, Long> {
}
