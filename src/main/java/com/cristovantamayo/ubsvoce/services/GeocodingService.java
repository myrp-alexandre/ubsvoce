package com.cristovantamayo.ubsvoce.services;

/**
 * Classes de Serviço para Geolocalização {@code GeocodingService}
 * Trabalha a busca à API do Google à consultas e filtro provenientes do banco de dados
 * 	
 */


import java.io.IOException;
import java.sql.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.cristovantamayo.ubsvoce.entities.Local;
import com.cristovantamayo.ubsvoce.entities.Unidade;
import com.cristovantamayo.ubsvoce.repositories.LocalRepository;
import com.cristovantamayo.ubsvoce.repositories.UnidadeRepository;
import com.cristovantamayo.ubsvoce.services.exceptions.GeocodingServiceException;
import com.cristovantamayo.ubsvoce.services.util.Paginador;
import com.google.maps.GeoApiContext;
import com.google.maps.GeocodingApi;
import com.google.maps.errors.ApiException;
import com.google.maps.model.GeocodingResult;

@Service
public class GeocodingService {
	
	
	private static Double lastLat;
	private static Double lastLng;
	
	public static Map<Double, Double> lastGeocodes(){
		Map<Double, Double> coods = new HashMap<>();
		coods.put(lastLat, lastLng);
		return coods;
	}
	
	@Autowired
	LocalRepository localRepository;
	
	@Autowired
	UnidadeRepository unidadeRepository;
	
	@Autowired
	GeocodingUtil geocodingUtil;
		
	/**
	 * Retorna lista de Unidades (UBS) dadas as Coordenadas e Raio de Acao (em metros)
	 * 
	 * @param lat   		coordenada latitude 
	 * @param lng   		coordenada longitude 
	 * @param raioDeAcao 	raio da area de cobertura (em Metros), distancia entre o ponto centrar as suas extremidades
	 * @param page			numero da pagina a ser exibida: quando em paginacao
	 * @param per_page		numero de maximo de unidade para ser exibido por pagina: quando em paginacao  
	 * @return novaLista	Lista de @type{Unidades} dentro area de cobertura
	 */
	public List<Unidade> retrieveNearUbs(Double lat, Double lng, Double raioDeAcao, Integer page, Integer per_page){
		
		/**
		 * PRIMEIRO FILTRO: Consulta ao Banco de Dados.
		 * Realiza uma busca por valores compativeis com a porcao inteira das coordenadas, restringe o range.
		 *
		 * A tecnica escolhida reduz o tempo de processamento em media 80% sem afetar a qualidade de resposta dentro
		 * da proposta do software, ou seja, pesquisas por Enderecos, Bairros e arredores. 
		 * Lembrando que cada 'grau' corresponde (por meio de calculos) à 112,12 Km.
		 */
		List<Unidade> lista = unidadeRepository.findPartialByLatAndLng((double) Math.round(lat), (double) Math.round(lng));
		
		/**
		 * SEGUNDO FILTRO: Filtragem por proximidade.
		 * Programacao funcional
		 * Uma vez stream, eh aplicado o filtro do 'raioDeAcao' em pipeline com
		 * ordernacao por proximidade em seguida o strem eh convertido ao seu tipo inicial List<>
		 */
		Stream<Unidade> stream = lista.stream();
		List<Unidade> novaLista = stream.filter(unidade -> geocodingUtil.isNear(unidade, lat, lng, raioDeAcao))
				.map(u -> {return new Unidade(u, geocodingUtil.calculaDistancia(lat, lng,u.getGeocode().getGeocode_lat(), u.getGeocode().getGeocode_long()));})
				.sorted((ubs1, ubs2) -> { return Double.compare(ubs1.getDistance(), ubs2.getDistance()); })
				.collect(Collectors.toList());
		 
		
		/**
		 * IMPORTANTE - ATENCAO ///////
		 *
		 * Referente a Paginacao por se tratar de um processo 'hibrido' de pesquisa entre Base de Dados e filtragem programatica,
		 * nao houve meio eficiente de limitacao de consulta no banco pois a filtragem subsequente defasa os limites de paginacao estipulados.
		 * Em ultimo caso, na necessidade de se haver paginacao, os limites serao realizados ja com o resultado final em maos.
		 * Quando possivel a opção de mais eficiente e usar valores altos em 'per_page' tratando a paginacao com recursos do front-end, lembrando
		 * que um retorno de pesquisa comum proposta pelo Aplicativo UBSVoce dificilmente retornara valores superiores a 50 Unidades por pesquisa.
		 */
		
		// Se nao tem per_page nem page, logo nao ha o que paginar, retorna lista
		if(per_page == null && page == null) return novaLista;
		
		// testa a lista 
		if(novaLista != null) {
			// calculculo do numero de paginas
			@SuppressWarnings("null")
			Integer pages = novaLista.size() / per_page;
			if(pages*per_page < novaLista.size()) pages++;
			
			// se uma unica pagina resolve, retorna lista
			if(1 == pages) {
				return novaLista;
				
			// 2 ou mais paginas
			} else {
				// Subdividir a novaLista e sublistas com o numero de elementos indicado por per_page
				Paginador<Unidade> paginas = Paginador.ofSize(novaLista, per_page);
				
				// O índice da página à ser retornada não pode passar do total de páginas menos 1, pois inicia em 0.
				if(page <= paginas.size()-1) {
					// Retornar a página indicada
					novaLista = paginas.get(page-1);
				} else {
					novaLista = null;
				}
			}
		}
		
		return novaLista;
	}
	
	
	
	/**
	 * Faz requisicao ah API do Google buscando pelo endereco textual
	 * Script fornecido pelo Google: https://github.com/googlemaps/google-maps-services-java
	 * 
	 * @param address endereco textual fornecido pelo usuario
	 * @param apiKey Credencial Geocoding API Google
	 * @return GeocodingResult @type{GeocodingResult} resultado fornecido pela Geocoding API do Google
	 */
	public static GeocodingResult[] searchAddress(String address, String apiKey) {
		GeoApiContext context = new GeoApiContext.Builder()
			    .apiKey(apiKey)
			    .build();
			GeocodingResult[] results;
			try {
				results = GeocodingApi.geocode(context,
				    address).await();
				return results;
			} catch (ApiException | InterruptedException | IOException e) {
				throw new GeocodingServiceException(e.getMessage());
			}
	}
	
	/**
	 * Salva em Banco a localizacao pesquisada
	 * Uma vez registrada não sera necessária a requisicao ao servicos do Google.
	 * TODO recurso nao totalmente implemnetado.
	 * @param formatedAddress endereco textual formatado retornado pela API do Google. 
	 * @param lat coordenada latitude
	 * @param lng coordenada longitude
	 */
	private void saveLocation(String formatedAddress, Double lat, Double lng) {
		Date searched_at = null;
		localRepository.save(new Local(formatedAddress, lat, lng, searched_at));
	}
	

}
