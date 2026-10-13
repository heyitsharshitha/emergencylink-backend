-- EmergencyLink baseline schema (MySQL 8 / MariaDB 10.6+).
-- Flyway runs this only on an empty database. Databases created earlier by Hibernate are baselined at version 1
-- (spring.flyway.baseline-on-migrate=true), so this file is skipped for them. Add all later changes as V2, V3, ...

CREATE TABLE IF NOT EXISTS specialties (
  id BIGINT NOT NULL AUTO_INCREMENT,
  name VARCHAR(80) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_specialties_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS facilities (
  id BIGINT NOT NULL AUTO_INCREMENT,
  name VARCHAR(80) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_facilities_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS hospitals (
  id BIGINT NOT NULL AUTO_INCREMENT,
  name VARCHAR(160) NOT NULL,
  type VARCHAR(30) NOT NULL,
  address VARCHAR(255),
  city VARCHAR(80) NOT NULL,
  state VARCHAR(80) NOT NULL,
  phone VARCHAR(30),
  email VARCHAR(160),
  emergency_contact VARCHAR(60),
  latitude DOUBLE,
  longitude DOUBLE,
  emergency_services BIT(1) NOT NULL,
  active BIT(1) NOT NULL,
  created_at DATETIME(6),
  updated_at DATETIME(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_hospitals_name (name),
  KEY idx_hospital_city (city),
  KEY idx_hospital_active (active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS hospital_specialties (
  hospital_id BIGINT NOT NULL,
  specialty_id BIGINT NOT NULL,
  PRIMARY KEY (hospital_id, specialty_id),
  CONSTRAINT fk_hs_hospital FOREIGN KEY (hospital_id) REFERENCES hospitals (id),
  CONSTRAINT fk_hs_specialty FOREIGN KEY (specialty_id) REFERENCES specialties (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS hospital_facilities (
  hospital_id BIGINT NOT NULL,
  facility_id BIGINT NOT NULL,
  PRIMARY KEY (hospital_id, facility_id),
  CONSTRAINT fk_hf_hospital FOREIGN KEY (hospital_id) REFERENCES hospitals (id),
  CONSTRAINT fk_hf_facility FOREIGN KEY (facility_id) REFERENCES facilities (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS hospital_resources (
  id BIGINT NOT NULL AUTO_INCREMENT,
  hospital_id BIGINT NOT NULL,
  type VARCHAR(30) NOT NULL,
  total INT NOT NULL,
  available INT NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  updated_by VARCHAR(120),
  PRIMARY KEY (id),
  UNIQUE KEY uk_hospital_resource (hospital_id, type),
  CONSTRAINT fk_hr_hospital FOREIGN KEY (hospital_id) REFERENCES hospitals (id),
  CONSTRAINT ck_hr_available CHECK (available >= 0 AND total >= 0 AND available <= total)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS users (
  id BIGINT NOT NULL AUTO_INCREMENT,
  email VARCHAR(160) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  full_name VARCHAR(120) NOT NULL,
  role VARCHAR(30) NOT NULL,
  hospital_id BIGINT,
  active BIT(1) NOT NULL,
  created_at DATETIME(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_users_email (email),
  KEY idx_users_hospital (hospital_id),
  CONSTRAINT fk_users_hospital FOREIGN KEY (hospital_id) REFERENCES hospitals (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS referrals (
  id BIGINT NOT NULL AUTO_INCREMENT,
  referral_code VARCHAR(40) NOT NULL,
  patient_name VARCHAR(120) NOT NULL,
  age INT,
  gender VARCHAR(20),
  emergency_condition VARCHAR(200) NOT NULL,
  clinical_summary VARCHAR(2000),
  urgency VARCHAR(20) NOT NULL,
  required_specialty_id BIGINT,
  icu_required BIT(1) NOT NULL,
  ventilator_required BIT(1) NOT NULL,
  oxygen_required BIT(1) NOT NULL,
  blood_required BIT(1) NOT NULL,
  blood_group VARCHAR(10),
  additional_requirements VARCHAR(1000),
  referring_hospital_id BIGINT NOT NULL,
  target_hospital_id BIGINT,
  created_by BIGINT,
  status VARCHAR(30) NOT NULL,
  admin_flagged BIT(1) NOT NULL DEFAULT b'0',
  flag_reason VARCHAR(500),
  flagged_at DATETIME(6),
  decision_reason VARCHAR(1000),
  decided_at DATETIME(6),
  created_at DATETIME(6),
  updated_at DATETIME(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_referrals_code (referral_code),
  KEY idx_referral_status (status),
  KEY idx_referral_urgency (urgency),
  KEY idx_referral_referring (referring_hospital_id),
  KEY idx_referral_target (target_hospital_id),
  KEY idx_referral_created (created_at),
  CONSTRAINT fk_ref_specialty FOREIGN KEY (required_specialty_id) REFERENCES specialties (id),
  CONSTRAINT fk_ref_referring FOREIGN KEY (referring_hospital_id) REFERENCES hospitals (id),
  CONSTRAINT fk_ref_target FOREIGN KEY (target_hospital_id) REFERENCES hospitals (id),
  CONSTRAINT fk_ref_created_by FOREIGN KEY (created_by) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS referral_facilities (
  referral_id BIGINT NOT NULL,
  facility_id BIGINT NOT NULL,
  PRIMARY KEY (referral_id, facility_id),
  CONSTRAINT fk_rf_referral FOREIGN KEY (referral_id) REFERENCES referrals (id),
  CONSTRAINT fk_rf_facility FOREIGN KEY (facility_id) REFERENCES facilities (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS referral_requirements (
  id BIGINT NOT NULL AUTO_INCREMENT,
  referral_id BIGINT NOT NULL,
  resource_type VARCHAR(30) NOT NULL,
  quantity INT NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT fk_rr_referral FOREIGN KEY (referral_id) REFERENCES referrals (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS referral_status_history (
  id BIGINT NOT NULL AUTO_INCREMENT,
  referral_id BIGINT NOT NULL,
  from_status VARCHAR(30),
  to_status VARCHAR(30) NOT NULL,
  event_type VARCHAR(40) NOT NULL,
  note VARCHAR(1000),
  actor_id BIGINT,
  actor_label VARCHAR(200),
  created_at DATETIME(6),
  PRIMARY KEY (id),
  KEY idx_history_referral (referral_id, created_at),
  CONSTRAINT fk_rsh_referral FOREIGN KEY (referral_id) REFERENCES referrals (id),
  CONSTRAINT fk_rsh_actor FOREIGN KEY (actor_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS referral_messages (
  id BIGINT NOT NULL AUTO_INCREMENT,
  referral_id BIGINT NOT NULL,
  sender_id BIGINT NOT NULL,
  sender_hospital_id BIGINT,
  body VARCHAR(2000) NOT NULL,
  created_at DATETIME(6),
  PRIMARY KEY (id),
  KEY idx_message_referral (referral_id, created_at),
  CONSTRAINT fk_rm_referral FOREIGN KEY (referral_id) REFERENCES referrals (id),
  CONSTRAINT fk_rm_sender FOREIGN KEY (sender_id) REFERENCES users (id),
  CONSTRAINT fk_rm_hospital FOREIGN KEY (sender_hospital_id) REFERENCES hospitals (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS notifications (
  id BIGINT NOT NULL AUTO_INCREMENT,
  recipient_id BIGINT NOT NULL,
  referral_id BIGINT,
  type VARCHAR(40) NOT NULL,
  title VARCHAR(160) NOT NULL,
  message VARCHAR(500),
  is_read BIT(1) NOT NULL,
  created_at DATETIME(6),
  PRIMARY KEY (id),
  KEY idx_notification_recipient (recipient_id, is_read, created_at),
  CONSTRAINT fk_n_recipient FOREIGN KEY (recipient_id) REFERENCES users (id),
  CONSTRAINT fk_n_referral FOREIGN KEY (referral_id) REFERENCES referrals (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS audit_events (
  id BIGINT NOT NULL AUTO_INCREMENT,
  actor_email VARCHAR(160),
  actor_role VARCHAR(30),
  action VARCHAR(60) NOT NULL,
  entity_type VARCHAR(40),
  entity_id BIGINT,
  entity_label VARCHAR(160),
  detail VARCHAR(500),
  reason VARCHAR(500),
  outcome VARCHAR(20),
  before_value VARCHAR(1000),
  after_value VARCHAR(1000),
  created_at DATETIME(6),
  PRIMARY KEY (id),
  KEY idx_audit_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS admin_notes (
  id BIGINT NOT NULL AUTO_INCREMENT,
  referral_id BIGINT NOT NULL,
  author_id BIGINT,
  author_label VARCHAR(200),
  body VARCHAR(2000) NOT NULL,
  created_at DATETIME(6),
  PRIMARY KEY (id),
  KEY idx_admin_note_referral (referral_id, created_at),
  CONSTRAINT fk_an_referral FOREIGN KEY (referral_id) REFERENCES referrals (id),
  CONSTRAINT fk_an_author FOREIGN KEY (author_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS resource_changes (
  id BIGINT NOT NULL AUTO_INCREMENT,
  hospital_id BIGINT NOT NULL,
  resource_type VARCHAR(30) NOT NULL,
  old_total INT,
  old_available INT,
  new_total INT NOT NULL,
  new_available INT NOT NULL,
  changed_by VARCHAR(200),
  reason VARCHAR(500),
  created_at DATETIME(6),
  PRIMARY KEY (id),
  KEY idx_resource_change_hospital (hospital_id, created_at),
  CONSTRAINT fk_rc_hospital FOREIGN KEY (hospital_id) REFERENCES hospitals (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS saved_filters (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  scope VARCHAR(20) NOT NULL,
  name VARCHAR(80) NOT NULL,
  filter_json VARCHAR(1000) NOT NULL,
  created_at DATETIME(6),
  PRIMARY KEY (id),
  KEY idx_saved_filter_user (user_id, scope),
  CONSTRAINT fk_sf_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS exception_states (
  exception_key VARCHAR(80) NOT NULL,
  status VARCHAR(20) NOT NULL,
  owner_name VARCHAR(160),
  owner_email VARCHAR(160),
  note VARCHAR(500),
  updated_at DATETIME(6),
  PRIMARY KEY (exception_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS system_settings (
  setting_key VARCHAR(60) NOT NULL,
  setting_value VARCHAR(200) NOT NULL,
  updated_by VARCHAR(160),
  updated_at DATETIME(6),
  PRIMARY KEY (setting_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
