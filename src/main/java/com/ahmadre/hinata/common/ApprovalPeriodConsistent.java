package com.ahmadre.hinata.common;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.LocalDate;

/**
 * A submission rhythm has to be answerable: the type and the parameters that go
 * with it must actually add up. Two weeks from nothing is not a period, and a
 * "monthly, every 14 days" would leave whoever computes the span (HIN-88) to
 * pick a winner silently.
 *
 * <p>Declared once and put on both the environment defaults
 * ({@code HinataProperties.TimeTracking.ApprovalPeriod}) and the stored override
 * ({@code ServerSettings.TimeTracking.ApprovalPeriod}) — the same rule for the
 * same shape, so a coherent default cannot be turned incoherent by a save.
 *
 * <p>An absent {@code type} means "use the environment default", which the
 * resolver reads as {@link TimePolicy.ApprovalPeriod#MONTHLY} when nothing is
 * configured; it is validated as monthly here for the same reason. The env
 * block is validated in its own right, so a stored block that inherits a
 * BIWEEKLY default inherits its anchor date with it.
 */
@Documented
@Constraint(validatedBy = ApprovalPeriodConsistent.Validator.class)
@Target({ ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
public @interface ApprovalPeriodConsistent {

	String message() default "error.timeTracking.approvalPeriodInvalid";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

	/**
	 * What the validator needs to see. Named as bean getters so both blocks
	 * satisfy it with the accessors Lombok already generates for them.
	 */
	interface Period {

		TimePolicy.ApprovalPeriod getType();

		LocalDate getAnchorDate();

		Integer getDays();
	}

	class Validator implements ConstraintValidator<ApprovalPeriodConsistent, Period> {

		@Override
		public boolean isValid(Period period, ConstraintValidatorContext context) {
			if (period == null) {
				return true;
			}
			TimePolicy.ApprovalPeriod type = period.getType() != null
					? period.getType()
					: TimePolicy.ApprovalPeriod.MONTHLY;
			boolean custom = type == TimePolicy.ApprovalPeriod.CUSTOM_DAYS;
			if (custom != (period.getDays() != null)) {
				return reject(context, "days");
			}
			boolean needsAnchor = custom || type == TimePolicy.ApprovalPeriod.BIWEEKLY;
			if (needsAnchor && period.getAnchorDate() == null) {
				return reject(context, "anchorDate");
			}
			return true;
		}

		/**
		 * Reports the violation on the field that is wrong rather than on the
		 * block, so the answer says which input to fix — a class-level violation
		 * would reach the client as a bare "validation failed".
		 */
		private boolean reject(ConstraintValidatorContext context, String field) {
			context.disableDefaultConstraintViolation();
			context.buildConstraintViolationWithTemplate(
					context.getDefaultConstraintMessageTemplate())
					.addPropertyNode(field)
					.addConstraintViolation();
			return false;
		}
	}
}
