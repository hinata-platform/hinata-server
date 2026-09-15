package com.ahmadre.hinata.board;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.issue.IssueLinkGraphService;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.user.User;
import com.mongodb.MongoExecutionTimeoutException;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.UncategorizedMongoDbException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A read the database gave up on after its time ends in a 503 the app can explain, not in a 500. */
class BoardReaderTimeoutTest {

	@Test
	void saysTheServerIsBusyWhenTheDatabaseGaveUpOnARead() {
		AgileBoardRepository boards = mock(AgileBoardRepository.class);
		when(boards.findById("b1")).thenReturn(Optional.of(AgileBoard.builder().id("b1").name("Board")
				.projectIds(new ArrayList<>(List.of("p1"))).build()));
		BoardAccess access = mock(BoardAccess.class);
		when(access.assertReadable(any(), any())).thenReturn(List.of(Project.builder().id("p1").key("HIN").name("Hinata").build()));
		MongoTemplate mongo = mock(MongoTemplate.class);
		when(mongo.aggregate(any(Aggregation.class), anyString(), eq(Document.class))).thenThrow(new UncategorizedMongoDbException(
				"gave up", new MongoExecutionTimeoutException(50, "operation exceeded time limit")));
		BoardReader reader = new BoardReader(boards, mock(SprintRepository.class), access, mock(BoardCardAssembler.class),
				mock(IssueLinkGraphService.class), mongo, Clock.systemUTC());

		assertThatThrownBy(() -> reader.wall("b1", null, BoardQuery.ALL, 30, User.builder().id("u1").build()))
				.isInstanceOfSatisfying(ApiException.class, ex -> {
					assertThat(ex.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
					assertThat(ex.getMessageKey()).isEqualTo("error.board.busy");
				});
	}
}
