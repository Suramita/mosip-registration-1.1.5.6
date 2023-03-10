/**
 * 
 */
package io.mosip.registration.processor.status.service.impl;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.mosip.kernel.core.dataaccess.exception.DataAccessLayerException;
import io.mosip.kernel.core.exception.ExceptionUtils;
import io.mosip.kernel.core.exception.IOException;
import io.mosip.kernel.core.idvalidator.exception.InvalidIDException;
import io.mosip.kernel.core.idvalidator.spi.RidValidator;
import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.kernel.core.util.JsonUtils;
import io.mosip.kernel.core.util.exception.JsonMappingException;
import io.mosip.kernel.core.util.exception.JsonParseException;
import io.mosip.kernel.core.util.exception.JsonProcessingException;
import io.mosip.kernel.idvalidator.rid.constant.RidExceptionProperty;
import io.mosip.registration.processor.core.code.EventId;
import io.mosip.registration.processor.core.code.EventName;
import io.mosip.registration.processor.core.code.EventType;
import io.mosip.registration.processor.core.code.ModuleName;
import io.mosip.registration.processor.core.constant.AuditLogConstant;
import io.mosip.registration.processor.core.constant.LoggerFileConstant;
import io.mosip.registration.processor.core.constant.ResponseStatusCode;
import io.mosip.registration.processor.core.exception.ApisResourceAccessException;
import io.mosip.registration.processor.core.exception.util.PlatformErrorMessages;
import io.mosip.registration.processor.core.exception.util.PlatformSuccessMessages;
import io.mosip.registration.processor.core.logger.LogDescription;
import io.mosip.registration.processor.core.logger.RegProcessorLogger;
import io.mosip.registration.processor.rest.client.audit.builder.AuditLogRequestBuilder;
import io.mosip.registration.processor.status.code.RegistrationExternalStatusCode;
import io.mosip.registration.processor.status.code.SupervisorStatus;
import io.mosip.registration.processor.status.dao.SyncRegistrationDao;
import io.mosip.registration.processor.status.decryptor.Decryptor;
import io.mosip.registration.processor.status.dto.RegistrationAdditionalInfoDTO;
import io.mosip.registration.processor.status.dto.RegistrationStatusDto;
import io.mosip.registration.processor.status.dto.RegistrationStatusSubRequestDto;
import io.mosip.registration.processor.status.dto.RegistrationSyncRequestDTO;
import io.mosip.registration.processor.status.dto.SyncRegistrationDto;
import io.mosip.registration.processor.status.dto.SyncResponseDto;
import io.mosip.registration.processor.status.dto.SyncResponseFailDto;
import io.mosip.registration.processor.status.dto.SyncResponseFailureDto;
import io.mosip.registration.processor.status.dto.SyncResponseSuccessDto;
import io.mosip.registration.processor.status.dto.SyncTypeDto;
import io.mosip.registration.processor.status.encryptor.Encryptor;
import io.mosip.registration.processor.status.entity.SyncRegistrationEntity;
import io.mosip.registration.processor.status.exception.EncryptionFailureException;
import io.mosip.registration.processor.status.exception.PacketDecryptionFailureException;
import io.mosip.registration.processor.status.exception.TablenotAccessibleException;
import io.mosip.registration.processor.status.service.SyncRegistrationService;
import io.mosip.registration.processor.status.utilities.RegistrationUtility;

/**
 * The Class SyncRegistrationServiceImpl.
 *
 * @author M1048399
 * @author M1048219
 * @author M1047487
 */
@Component
public class SyncRegistrationServiceImpl implements SyncRegistrationService<SyncResponseDto, SyncRegistrationDto> {

	/** The Constant CREATED_BY. */
	private static final String CREATED_BY = "MOSIP";

	/** The event id. */
	private String eventId = "";

	/** The event name. */
	private String eventName = "";

	/** The event type. */
	private String eventType = "";

	/** The sync registration dao. */
	@Autowired
	private SyncRegistrationDao syncRegistrationDao;

	/** The core audit request builder. */
	@Autowired
	private AuditLogRequestBuilder auditLogRequestBuilder;

	/** The rid validator. */
	@Autowired
	private RidValidator<String> ridValidator;

	/** The lancode length. */
	private int LANCODE_LENGTH = 3;

	/** The reg proc logger. */
	private static Logger regProcLogger = RegProcessorLogger.getLogger(SyncRegistrationServiceImpl.class);

	/** The decryptor. */
	@Autowired
	private Decryptor decryptor;
	
	/** The encryptor. */
	@Autowired
	private Encryptor encryptor;

	/**
	 * Instantiates a new sync registration service impl.
	 */
	public SyncRegistrationServiceImpl() {
		super();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * io.mosip.registration.processor.status.service.SyncRegistrationService#sync(
	 * java.util.List)
	 */
	public List<SyncResponseDto> sync(List<SyncRegistrationDto> resgistrationDtos, String referenceId,
			String timeStamp) {
		List<SyncResponseDto> synchResponseList = new ArrayList<>();
		regProcLogger.debug(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.USERID.toString(), "",
				"SyncRegistrationServiceImpl::sync()::entry");
		LogDescription description = new LogDescription();
		boolean isTransactionSuccessful = false;
		try {
			for (SyncRegistrationDto registrationDto : resgistrationDtos) {
				synchResponseList = validateSync(registrationDto, synchResponseList, referenceId, timeStamp);
			}
			isTransactionSuccessful = true;
			description.setMessage("Registartion Id's are successfully synched in Sync Registration table");

			regProcLogger.info(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
					"", "");
		} catch (DataAccessLayerException e) {
			description.setMessage(PlatformErrorMessages.RPR_RGS_DATA_ACCESS_EXCEPTION.getMessage());
			description.setCode(PlatformErrorMessages.RPR_RGS_DATA_ACCESS_EXCEPTION.getCode());
			description.setMessage("DataAccessLayerException while syncing Registartion Id's" + "::" + e.getMessage());

			regProcLogger.error(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
					"", e.getMessage() + ExceptionUtils.getStackTrace(e));
			throw new TablenotAccessibleException(
					PlatformErrorMessages.RPR_RGS_REGISTRATION_TABLE_NOT_ACCESSIBLE.getMessage(), e);
		} finally {
			if (isTransactionSuccessful) {
				eventName = eventId.equalsIgnoreCase(EventId.RPR_402.toString()) ? EventName.UPDATE.toString()
						: EventName.ADD.toString();
				eventType = EventType.BUSINESS.toString();
			} else {
				description.setMessage(PlatformErrorMessages.RPR_RGS_REGISTRATION_SYNC_SERVICE_FAILED.getMessage());
				description.setCode(PlatformErrorMessages.RPR_RGS_REGISTRATION_SYNC_SERVICE_FAILED.getCode());
				eventId = EventId.RPR_405.toString();
				eventName = EventName.EXCEPTION.toString();
				eventType = EventType.SYSTEM.toString();
			}
			/** Module-Id can be Both Success/Error code */
			String moduleId = isTransactionSuccessful
					? PlatformSuccessMessages.RPR_SYNC_REGISTRATION_SERVICE_SUCCESS.getCode()
					: description.getCode();
			String moduleName = ModuleName.SYNC_REGISTRATION_SERVICE.toString();
			auditLogRequestBuilder.createAuditRequestBuilder(description.getMessage(), eventId, eventName, eventType,
					moduleId, moduleName, AuditLogConstant.MULTIPLE_ID.toString());

		}
		regProcLogger.debug(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.USERID.toString(), "",
				"SyncRegistrationServiceImpl::sync()::exit");
		return synchResponseList;

	}

	/**
	 * Validate RegiId with Kernel RidValiator.
	 *
	 * @param registrationDto
	 *            the registration dto
	 * @param syncResponseList
	 *            the sync response list
	 * @return the list
	 */
	private List<SyncResponseDto> validateSync(SyncRegistrationDto registrationDto,
			List<SyncResponseDto> syncResponseList, String referenceId,
			String timeStamp) {
		if (validateLanguageCode(registrationDto, syncResponseList)
				&& validateRegistrationType(registrationDto, syncResponseList)
				&& validateHashValue(registrationDto, syncResponseList)
				&& validateSupervisorStatus(registrationDto, syncResponseList)) {
			if (validateRegistrationID(registrationDto, syncResponseList)) {
				SyncResponseFailureDto syncResponseFailureDto = new SyncResponseFailureDto();
				try {
					if (ridValidator.validateId(registrationDto.getRegistrationId())) {

						syncResponseList = validateRegId(registrationDto, syncResponseList, referenceId, timeStamp);

					}
				} catch (InvalidIDException e) {
					syncResponseFailureDto.setRegistrationId(registrationDto.getRegistrationId());

					syncResponseFailureDto.setStatus(ResponseStatusCode.FAILURE.toString());
					if (e.getErrorCode().equals(RidExceptionProperty.INVALID_RID_LENGTH.getErrorCode())) {
						syncResponseFailureDto
								.setMessage(PlatformErrorMessages.RPR_RGS_INVALID_REGISTRATIONID_LENGTH.getMessage());
						syncResponseFailureDto
								.setErrorCode(PlatformErrorMessages.RPR_RGS_INVALID_REGISTRATIONID_LENGTH.getCode());
					} else if (e.getErrorCode().equals(RidExceptionProperty.INVALID_RID.getErrorCode())) {
						syncResponseFailureDto
								.setMessage(PlatformErrorMessages.RPR_RGS_INVALID_REGISTRATIONID.getMessage());
						syncResponseFailureDto
								.setErrorCode(PlatformErrorMessages.RPR_RGS_INVALID_REGISTRATIONID.getCode());
					} else if (e.getErrorCode().equals(RidExceptionProperty.INVALID_RID_TIMESTAMP.getErrorCode())) {
						syncResponseFailureDto.setMessage(
								PlatformErrorMessages.RPR_RGS_INVALID_REGISTRATIONID_TIMESTAMP.getMessage());
						syncResponseFailureDto
								.setErrorCode(PlatformErrorMessages.RPR_RGS_INVALID_REGISTRATIONID_TIMESTAMP.getCode());
					}
					syncResponseList.add(syncResponseFailureDto);
				}
			}
		}
		return syncResponseList;
	}

	/**
	 * Validate supervisor status.
	 *
	 * @param registrationDto
	 *            the registration dto
	 * @param syncResponseList
	 *            the sync response list
	 * @return true, if successful
	 */
	private boolean validateSupervisorStatus(SyncRegistrationDto registrationDto,
			List<SyncResponseDto> syncResponseList) {
		String value = registrationDto.getSupervisorStatus();
		if (SupervisorStatus.APPROVED.toString().equals(value)) {
			return true;
		} else if (SupervisorStatus.REJECTED.toString().equals(value)) {
			return true;

		} else {
			SyncResponseFailureDto syncResponseFailureDto = new SyncResponseFailureDto();
			syncResponseFailureDto.setRegistrationId(registrationDto.getRegistrationId());

			syncResponseFailureDto.setStatus(ResponseStatusCode.FAILURE.toString());
			syncResponseFailureDto.setMessage(PlatformErrorMessages.RPR_RGS_INVALID_SUPERVISOR_STATUS.getMessage());
			syncResponseFailureDto.setErrorCode(PlatformErrorMessages.RPR_RGS_INVALID_SUPERVISOR_STATUS.getCode());
			syncResponseList.add(syncResponseFailureDto);
			return false;
		}

	}

	/**
	 * Validate hash value.
	 *
	 * @param registrationDto
	 *            the registration dto
	 * @param syncResponseList
	 *            the sync response list
	 * @return true, if successful
	 */
	private boolean validateHashValue(SyncRegistrationDto registrationDto, List<SyncResponseDto> syncResponseList) {

		if (registrationDto.getPacketHashValue() == null) {
			SyncResponseFailureDto syncResponseFailureDto = new SyncResponseFailureDto();
			syncResponseFailureDto.setRegistrationId(registrationDto.getRegistrationId());

			syncResponseFailureDto.setStatus(ResponseStatusCode.FAILURE.toString());
			syncResponseFailureDto.setMessage(PlatformErrorMessages.RPR_RGS_INVALID_HASHVALUE.getMessage());
			syncResponseFailureDto.setErrorCode(PlatformErrorMessages.RPR_RGS_INVALID_HASHVALUE.getCode());
			syncResponseList.add(syncResponseFailureDto);
			return false;
		} else {
			return true;
		}
	}

	/**
	 * Validate status code.
	 *
	 * @param registrationDto
	 *            the registration dto
	 * @param syncResponseList
	 *            the sync response list
	 * @return true, if successful
	 */
	private boolean validateRegistrationType(SyncRegistrationDto registrationDto,
			List<SyncResponseDto> syncResponseList) {

		String value = registrationDto.getRegistrationType();
		if (SyncTypeDto.NEW.getValue().equals(value)) {
			return true;
		} else if (SyncTypeDto.UPDATE.getValue().equals(value)) {
			return true;
		} else if (SyncTypeDto.LOST.getValue().equals(value)) {
			return true;
		} else if (SyncTypeDto.ACTIVATED.getValue().equals(value)) {
			return true;
		} else if (SyncTypeDto.DEACTIVATED.getValue().equals(value)) {
			return true;
		} else if (SyncTypeDto.RES_UPDATE.getValue().equals(value)) {
			return true;
		} else if (SyncTypeDto.RES_REPRINT.getValue().equals(value)) {
			return true;
		} else {
			SyncResponseFailureDto syncResponseFailureDto = new SyncResponseFailureDto();
			syncResponseFailureDto.setRegistrationId(registrationDto.getRegistrationId());

			syncResponseFailureDto.setStatus(ResponseStatusCode.FAILURE.toString());
			syncResponseFailureDto.setMessage(PlatformErrorMessages.RPR_RGS_INVALID_SYNCTYPE.getMessage());
			syncResponseFailureDto.setErrorCode(PlatformErrorMessages.RPR_RGS_INVALID_SYNCTYPE.getCode());
			syncResponseList.add(syncResponseFailureDto);
			return false;
		}
	}

	/**
	 * Validate language code.
	 *
	 * @param registrationDto
	 *            the registration dto
	 * @param syncResponseList
	 *            the sync response list
	 * @return true, if successful
	 */
	private boolean validateLanguageCode(SyncRegistrationDto registrationDto, List<SyncResponseDto> syncResponseList) {
		if (registrationDto.getLangCode().length() == LANCODE_LENGTH) {
			return true;
		} else {
			SyncResponseFailureDto syncResponseFailureDto = new SyncResponseFailureDto();
			syncResponseFailureDto.setRegistrationId(registrationDto.getRegistrationId());

			syncResponseFailureDto.setStatus(ResponseStatusCode.FAILURE.toString());
			syncResponseFailureDto.setMessage(PlatformErrorMessages.RPR_RGS_INVALID_LANGUAGECODE.getMessage());
			syncResponseFailureDto.setErrorCode(PlatformErrorMessages.RPR_RGS_INVALID_LANGUAGECODE.getCode());
			syncResponseList.add(syncResponseFailureDto);
			return false;
		}
	}

	/**
	 * Validate registration ID.
	 *
	 * @param registrationDto
	 *            the registration dto
	 * @param syncResponseList
	 *            the sync response list
	 * @return true, if successful
	 */
	private boolean validateRegistrationID(SyncRegistrationDto registrationDto,
			List<SyncResponseDto> syncResponseList) {
		if (registrationDto.getRegistrationId() != null) {
			return true;
		} else {
			SyncResponseFailureDto syncResponseFailureDto = new SyncResponseFailureDto();
			syncResponseFailureDto.setRegistrationId(registrationDto.getRegistrationId());
			syncResponseFailureDto.setStatus(ResponseStatusCode.FAILURE.toString());
			syncResponseFailureDto.setErrorCode(PlatformErrorMessages.RPR_RGS_EMPTY_REGISTRATIONID.getCode());
			syncResponseFailureDto.setMessage(PlatformErrorMessages.RPR_RGS_EMPTY_REGISTRATIONID.getMessage());
			syncResponseList.add(syncResponseFailureDto);
			return false;
		}
	}

	/**
	 * Validate reg id.
	 *
	 * @param registrationDto
	 *            the registration dto
	 * @param syncResponseList
	 *            the sync response list
	 * @return the list
	 */
	public List<SyncResponseDto> validateRegId(SyncRegistrationDto registrationDto,
			List<SyncResponseDto> syncResponseList, String referenceId,
			String timeStamp) {
		regProcLogger.debug(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.USERID.toString(),
				registrationDto.getRegistrationId(), "SyncRegistrationServiceImpl::validateRegId()::entry");
		SyncResponseSuccessDto syncResponseDto = new SyncResponseSuccessDto();
		SyncRegistrationEntity existingSyncRegistration = findByRegistrationId(
				registrationDto.getRegistrationId().trim());
		SyncRegistrationEntity syncRegistration;
		if (existingSyncRegistration != null) {
			// update sync registration record
			syncRegistration = convertDtoToEntity(registrationDto, referenceId, timeStamp);
			syncRegistration.setId(existingSyncRegistration.getId());
			syncRegistration.setCreateDateTime(existingSyncRegistration.getCreateDateTime());
			syncRegistrationDao.update(syncRegistration);
			syncResponseDto.setRegistrationId(registrationDto.getRegistrationId());

			eventId = EventId.RPR_402.toString();
		} else {
			// first time sync registration

			syncRegistration = convertDtoToEntity(registrationDto, referenceId, timeStamp);
			syncRegistration.setCreateDateTime(LocalDateTime.now(ZoneId.of("UTC")));
			syncRegistration.setId(RegistrationUtility.generateId());
			syncRegistrationDao.save(syncRegistration);
			syncResponseDto.setRegistrationId(registrationDto.getRegistrationId());

			eventId = EventId.RPR_407.toString();
		}
		syncResponseDto.setStatus(ResponseStatusCode.SUCCESS.toString());
		syncResponseList.add(syncResponseDto);
		regProcLogger.debug(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.USERID.toString(),
				registrationDto.getRegistrationId(), "SyncRegistrationServiceImpl::validateRegId()::exit");
		return syncResponseList;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see io.mosip.registration.processor.status.service.SyncRegistrationService#
	 * isPresent(java.lang.String)
	 */
	@Override
	public boolean isPresent(String registrationId) {
		return findByRegistrationId(registrationId) != null;
	}

	/**
	 * Find by registration id.
	 *
	 * @param registrationId
	 *            the registration id
	 * @return the sync registration entity
	 */
	@Override
	public SyncRegistrationEntity findByRegistrationId(String registrationId) {
		return syncRegistrationDao.findById(registrationId);
	}

	/**
	 * Convert dto to entity.
	 *
	 * @param dto
	 *            the dto
	 * @return the sync registration entity
	 */
	private SyncRegistrationEntity convertDtoToEntity(SyncRegistrationDto dto, String referenceId,
			String timeStamp) {
		SyncRegistrationEntity syncRegistrationEntity = new SyncRegistrationEntity();
		syncRegistrationEntity.setRegistrationId(dto.getRegistrationId().trim());
		syncRegistrationEntity.setIsDeleted(dto.getIsDeleted() != null ? dto.getIsDeleted() : Boolean.FALSE);
		syncRegistrationEntity.setLangCode(dto.getLangCode());
		syncRegistrationEntity.setRegistrationType(dto.getRegistrationType());
		syncRegistrationEntity.setPacketHashValue(dto.getPacketHashValue());
		syncRegistrationEntity.setPacketSize(dto.getPacketSize());
		syncRegistrationEntity.setSupervisorStatus(dto.getSupervisorStatus());
		syncRegistrationEntity.setSupervisorComment(dto.getSupervisorComment());
		syncRegistrationEntity.setUpdateDateTime(LocalDateTime.now(ZoneId.of("UTC")));

		try {
			RegistrationAdditionalInfoDTO regAdditionalInfo = new RegistrationAdditionalInfoDTO();
			regAdditionalInfo.setName(dto.getName());
			regAdditionalInfo.setEmail(dto.getEmail());
			regAdditionalInfo.setPhone(dto.getPhone());
			
			String additionalInfo = JsonUtils.javaObjectToJsonString(regAdditionalInfo);
			byte[] encryptedInfo = encryptor.encrypt(additionalInfo, referenceId, timeStamp);
			syncRegistrationEntity.setOptionalValues(encryptedInfo);			
		} catch(JsonProcessingException | EncryptionFailureException | ApisResourceAccessException exception) {
			regProcLogger.error(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
					"", exception.getMessage() + ExceptionUtils.getStackTrace(exception));
		}		

		syncRegistrationEntity.setCreatedBy(CREATED_BY);
		syncRegistrationEntity.setUpdatedBy(CREATED_BY);
		if (syncRegistrationEntity.getIsDeleted() != null && syncRegistrationEntity.getIsDeleted()) {
			syncRegistrationEntity.setDeletedDateTime(LocalDateTime.now(ZoneId.of("UTC")));
		} else {
			syncRegistrationEntity.setDeletedDateTime(null);
		}

		return syncRegistrationEntity;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see io.mosip.registration.processor.status.service.SyncRegistrationService#
	 * decryptAndGetSyncRequest(java.lang.Object, java.lang.String,
	 * java.lang.String, java.util.List)
	 */
	@Override
	public RegistrationSyncRequestDTO decryptAndGetSyncRequest(Object encryptedSyncMetaInfo, String referenceId,
			String timeStamp, List<SyncResponseDto> syncResponseList) {
		regProcLogger.debug(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.USERID.toString(), "",
				"SyncRegistrationServiceImpl::decryptAndGetSyncRequest()::entry");

		RegistrationSyncRequestDTO registrationSyncRequestDTO = null;
		try {
			String decryptedSyncMetaData = decryptor.decrypt(encryptedSyncMetaInfo, referenceId, timeStamp);
			registrationSyncRequestDTO = (RegistrationSyncRequestDTO) JsonUtils
					.jsonStringToJavaObject(RegistrationSyncRequestDTO.class, decryptedSyncMetaData);

		} catch (PacketDecryptionFailureException | ApisResourceAccessException e) {
			regProcLogger.error(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
					"", e.getMessage() + ExceptionUtils.getStackTrace(e));
			SyncResponseFailDto syncResponseFailureDto = new SyncResponseFailDto();

			syncResponseFailureDto.setStatus(ResponseStatusCode.FAILURE.toString());
			syncResponseFailureDto.setMessage(PlatformErrorMessages.RPR_RGS_DECRYPTION_FAILED.getMessage());
			syncResponseFailureDto.setErrorCode(PlatformErrorMessages.RPR_RGS_DECRYPTION_FAILED.getCode());
			syncResponseList.add(syncResponseFailureDto);
		} catch (JsonParseException e) {
			regProcLogger.error(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
					"", e.getMessage() + ExceptionUtils.getStackTrace(e));
			SyncResponseFailDto syncResponseFailureDto = new SyncResponseFailDto();

			syncResponseFailureDto.setStatus(ResponseStatusCode.FAILURE.toString());
			syncResponseFailureDto.setMessage(PlatformErrorMessages.RPR_RGS_JSON_PARSING_EXCEPTION.getMessage());
			syncResponseFailureDto.setErrorCode(PlatformErrorMessages.RPR_RGS_JSON_PARSING_EXCEPTION.getCode());
			syncResponseList.add(syncResponseFailureDto);

		} catch (JsonMappingException e) {
			regProcLogger.error(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
					"", e.getMessage() + ExceptionUtils.getStackTrace(e));
			SyncResponseFailDto syncResponseFailureDto = new SyncResponseFailDto();

			syncResponseFailureDto.setStatus(ResponseStatusCode.FAILURE.toString());
			syncResponseFailureDto.setMessage(PlatformErrorMessages.RPR_RGS_JSON_MAPPING_EXCEPTION.getMessage());
			syncResponseFailureDto.setErrorCode(PlatformErrorMessages.RPR_RGS_JSON_MAPPING_EXCEPTION.getCode());
			syncResponseList.add(syncResponseFailureDto);
		} catch (IOException e) {
			regProcLogger.error(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
					"", e.getMessage() + ExceptionUtils.getStackTrace(e));
			SyncResponseFailDto syncResponseFailureDto = new SyncResponseFailDto();

			syncResponseFailureDto.setStatus(ResponseStatusCode.FAILURE.toString());
			syncResponseFailureDto.setMessage(PlatformErrorMessages.RPR_SYS_IO_EXCEPTION.getMessage());
			syncResponseFailureDto.setErrorCode(PlatformErrorMessages.RPR_SYS_IO_EXCEPTION.getCode());
			syncResponseList.add(syncResponseFailureDto);
		}
		regProcLogger.debug(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.USERID.toString(), "",
				"SyncRegistrationServiceImpl::decryptAndGetSyncRequest()::exit");

		return registrationSyncRequestDTO;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see io.mosip.registration.processor.status.service.SyncRegistrationService#
	 * getByIds(java.util.List)
	 */
	@Override
	public List<RegistrationStatusDto> getByIds(List<RegistrationStatusSubRequestDto> requestIds) {
		regProcLogger.debug(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.USERID.toString(), "",
				"SyncRegistrationServiceImpl::getByIds()::entry");

		try {
			List<String> registrationIds = new ArrayList<>();

			for (RegistrationStatusSubRequestDto registrationStatusSubRequestDto : requestIds) {
				registrationIds.add(registrationStatusSubRequestDto.getRegistrationId());
			}
			if (!registrationIds.isEmpty()) {
				List<SyncRegistrationEntity> syncRegistrationEntityList = syncRegistrationDao.getByIds(registrationIds);

				regProcLogger.debug(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.USERID.toString(), "",
						"SyncRegistrationServiceImpl::getByIds()::exit");
				return convertEntityListToDtoListAndGetExternalStatus(syncRegistrationEntityList);
			}
			return null;
		} catch (DataAccessLayerException e) {

			regProcLogger.error(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
					"", e.getMessage() + ExceptionUtils.getStackTrace(e));
			throw new TablenotAccessibleException(
					PlatformErrorMessages.RPR_RGS_REGISTRATION_TABLE_NOT_ACCESSIBLE.getMessage(), e);
		}

	}

	/**
	 * Convert entity list to dto list and get external status.
	 *
	 * @param syncRegistrationEntityList
	 *            the sync registration entity list
	 * @return the list
	 */
	private List<RegistrationStatusDto> convertEntityListToDtoListAndGetExternalStatus(
			List<SyncRegistrationEntity> syncRegistrationEntityList) {
		List<RegistrationStatusDto> list = new ArrayList<>();
		if (syncRegistrationEntityList != null) {
			for (SyncRegistrationEntity entity : syncRegistrationEntityList) {
				list.add(convertEntityToDtoAndGetExternalStatus(entity));
			}

		}
		return list;
	}

	/**
	 * Convert entity to dto and get external status.
	 *
	 * @param entity
	 *            the entity
	 * @return the registration status dto
	 */
	private RegistrationStatusDto convertEntityToDtoAndGetExternalStatus(SyncRegistrationEntity entity) {
		RegistrationStatusDto registrationStatusDto = new RegistrationStatusDto();
		registrationStatusDto.setRegistrationId(entity.getRegistrationId());
		registrationStatusDto.setStatusCode(RegistrationExternalStatusCode.UPLOAD_PENDING.toString());
		return registrationStatusDto;
	}

	@Override
	public boolean deleteAdditionalInfo(SyncRegistrationEntity syncEntity) {
		return syncRegistrationDao.deleteAdditionalInfo(syncEntity);
	}
}
