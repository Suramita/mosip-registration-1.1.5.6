package io.mosip.registration.processor.packet.uploader.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 
 * @author Girish Yarru
 *
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@ApiModel(description = "Model representing a Crypto-Manager-Service Response")
public class CryptomanagerResponseDto {
	/**
	 * Data Encrypted/Decrypted in BASE64 encoding
	 */
	@ApiModelProperty(notes = "Data encrypted/decrypted in BASE64 encoding")
	private String data;
}
